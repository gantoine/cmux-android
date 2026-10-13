package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/gantoine/cmux-android/relay/internal/cmux"
	"github.com/gantoine/cmux-android/relay/internal/server"
)

const (
	defaultListenAddress = "127.0.0.1:8765"
	launchdLabel         = "dev.cmuxandroid.relay"
)

type paths struct {
	dir     string
	devices string
	admin   string
	plist   string
	log     string
}

func statePaths() (paths, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return paths{}, err
	}
	dir := os.Getenv("CMUX_RELAY_STATE_DIR")
	if dir == "" {
		dir = filepath.Join(home, "Library", "Application Support", "cmux-android-relay")
	}
	admin, err := adminSocketPath(dir)
	if err != nil {
		return paths{}, err
	}
	return paths{
		dir:     dir,
		devices: filepath.Join(dir, "devices.json"),
		admin:   admin,
		plist:   filepath.Join(home, "Library", "LaunchAgents", launchdLabel+".plist"),
		log:     filepath.Join(home, "Library", "Logs", "cmux-relay.log"),
	}, nil
}

const maxUnixSocketPath = 100

func adminSocketPath(stateDir string) (string, error) {
	preferred := filepath.Join(stateDir, "admin.sock")
	if len(preferred) <= maxUnixSocketPath {
		return preferred, nil
	}
	privateDir := filepath.Join("/tmp", "cmux-relay-"+strconv.Itoa(os.Getuid()))
	if err := os.Mkdir(privateDir, 0o700); err != nil && !errors.Is(err, os.ErrExist) {
		return "", err
	}
	info, err := os.Lstat(privateDir)
	if err != nil {
		return "", err
	}
	owner, ok := info.Sys().(*syscall.Stat_t)
	if !info.IsDir() || info.Mode().Perm() != 0o700 || !ok || int(owner.Uid) != os.Getuid() {
		return "", fmt.Errorf("%s must be a directory owned by you with mode 0700", privateDir)
	}
	digest := sha256.Sum256([]byte(stateDir))
	return filepath.Join(privateDir, hex.EncodeToString(digest[:4])+".sock"), nil
}

func defaultMacName() string {
	if output, err := exec.Command("scutil", "--get", "ComputerName").Output(); err == nil {
		if name := strings.TrimSpace(string(output)); name != "" {
			return name
		}
	}
	hostname, _ := os.Hostname()
	return strings.TrimSuffix(hostname, ".local")
}

func tailscaleBinary() (string, error) {
	if path, err := exec.LookPath("tailscale"); err == nil {
		return path, nil
	}
	appBinary := "/Applications/Tailscale.app/Contents/MacOS/Tailscale"
	if _, err := os.Stat(appBinary); err == nil {
		return appBinary, nil
	}
	return "", errors.New("tailscale is not installed; install it from https://tailscale.com/download")
}

func listenPort(listen string) string {
	_, port, err := net.SplitHostPort(listen)
	if err != nil {
		return "8765"
	}
	return port
}

func tailscaleRelayURL() (string, error) {
	binary, err := tailscaleBinary()
	if err != nil {
		return "", err
	}
	output, err := exec.Command(binary, "status", "--json").Output()
	if err != nil {
		return "", errors.New("could not read `tailscale status`; is Tailscale running and logged in?")
	}
	var status struct {
		Self struct {
			DNSName string `json:"DNSName"`
		} `json:"Self"`
	}
	if err := json.Unmarshal(output, &status); err != nil || status.Self.DNSName == "" {
		return "", errors.New("could not find this Mac's Tailscale name")
	}
	host := strings.TrimSuffix(status.Self.DNSName, ".")
	return "https://" + host + ":" + listenPort(defaultListenAddress), nil
}

func tailscaleServeCommand(listen string) string {
	port := listenPort(listen)
	return fmt.Sprintf("tailscale serve --bg --https=%s http://127.0.0.1:%s", port, port)
}

func runInstall(args []string) error {
	listen, macName, err := serveFlags("install", args)
	if err != nil {
		return err
	}
	p, err := statePaths()
	if err != nil {
		return err
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p.plist), 0o755); err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p.log), 0o755); err != nil {
		return err
	}
	plist := launchdPlist([]string{executable, "serve", "--listen", listen, "--name", macName}, p.log)
	if err := os.WriteFile(p.plist, []byte(plist), 0o644); err != nil {
		return err
	}
	domain := "gui/" + strconv.Itoa(os.Getuid())
	_ = exec.Command("launchctl", "bootout", domain+"/"+launchdLabel).Run()
	if output, err := exec.Command("launchctl", "bootstrap", domain, p.plist).CombinedOutput(); err != nil {
		return fmt.Errorf("launchctl bootstrap failed: %s", strings.TrimSpace(string(output)))
	}
	fmt.Println("The relay now runs in the background and starts when you log in.")
	fmt.Println("Log file:", p.log)
	fmt.Println()
	fmt.Println("Next:")
	fmt.Println("  1. Let the phone reach it over Tailscale:  " + tailscaleServeCommand(listen))
	fmt.Println("  2. Check the setup:                        cmux-relay doctor")
	fmt.Println("  3. Pair your phone:                        cmux-relay pair")
	return nil
}

func runUninstall() error {
	p, err := statePaths()
	if err != nil {
		return err
	}
	_ = exec.Command("launchctl", "bootout", "gui/"+strconv.Itoa(os.Getuid())+"/"+launchdLabel).Run()
	if err := os.Remove(p.plist); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	fmt.Println("The relay is stopped and removed from login items. Paired phones are kept in", p.devices)
	return nil
}

func launchdPlist(arguments []string, logPath string) string {
	var programArguments strings.Builder
	for _, argument := range arguments {
		programArguments.WriteString("\t\t<string>" + xmlEscape(argument) + "</string>\n")
	}
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	<string>` + launchdLabel + `</string>
	<key>ProgramArguments</key>
	<array>
` + programArguments.String() + `	</array>
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<true/>
	<key>ProcessType</key>
	<string>Interactive</string>
	<key>StandardOutPath</key>
	<string>` + xmlEscape(logPath) + `</string>
	<key>StandardErrorPath</key>
	<string>` + xmlEscape(logPath) + `</string>
</dict>
</plist>
`
}

func xmlEscape(value string) string {
	var buffer bytes.Buffer
	_ = xml.EscapeText(&buffer, []byte(value))
	return buffer.String()
}

func runDoctor(args []string) error {
	listen, _, err := serveFlags("doctor", args)
	if err != nil {
		return err
	}
	p, err := statePaths()
	if err != nil {
		return err
	}
	problems := 0
	check := func(ok bool, good, bad string) {
		if ok {
			fmt.Println("  ✓ " + good)
			return
		}
		problems++
		fmt.Println("  ✗ " + bad)
	}

	fmt.Println("cmux")
	socketPath := cmux.DefaultSocketPath()
	_, statErr := os.Stat(socketPath)
	check(statErr == nil, "socket found at "+socketPath, "no cmux socket at "+socketPath+"; is cmux running?")

	client := cmux.NewClient(socketPath, cmux.DefaultPasswordSource())
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	identity, err := client.Identify(ctx)
	check(err == nil, "connected to cmux "+identity.Version, fmt.Sprintf("could not talk to cmux: %v", err))

	var capabilities struct {
		AccessMode string `json:"access_mode"`
	}
	if err == nil {
		_ = client.Call(ctx, "system.capabilities", nil, &capabilities)
		mode := capabilities.AccessMode
		launchdAllowed := mode == "password" || mode == "automation" || mode == "allowAll"
		check(launchdAllowed,
			"socket access mode is "+mode+", which lets the background relay connect",
			"socket access mode is "+mode+"; the background relay will be refused.\n"+
				"      In cmux, open Settings > Automation and set socket access to Password,\n"+
				"      or set \"automation\": {\"socketControlMode\": \"password\"} in ~/.config/cmux/cmux.json")
	}

	fmt.Println("Relay")
	_, adminErr := server.CallAdmin(p.admin, server.AdminRequest{Command: "devices"})
	check(adminErr == nil, "relay is running", "relay is not running; run `cmux-relay install`")

	fmt.Println("Tailscale")
	binary, tailscaleErr := tailscaleBinary()
	check(tailscaleErr == nil, "tailscale found", "tailscale is not installed; install it from https://tailscale.com/download")
	if tailscaleErr == nil {
		output, err := exec.Command(binary, "serve", "status", "--json").Output()
		target := "127.0.0.1:" + listenPort(listen)
		check(err == nil && bytes.Contains(output, []byte(target)),
			"tailscale serve forwards to "+target,
			"tailscale serve is not forwarding to the relay; run: "+tailscaleServeCommand(listen))
	}

	fmt.Println()
	if problems > 0 {
		return fmt.Errorf("%d problem(s) found", problems)
	}
	fmt.Println("Everything looks good. Run `cmux-relay pair` to add a phone.")
	return nil
}
