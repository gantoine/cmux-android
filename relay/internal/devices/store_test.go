package devices

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPairingCodeWorksOnceAndTokenSurvivesReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "devices.json")
	store, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	code, _, err := store.NewPairingCode()
	if err != nil {
		t.Fatal(err)
	}

	formatted := strings.ToLower(code[:4] + "-" + code[4:])
	device, token, err := store.Pair(formatted, "Pixel")
	if err != nil {
		t.Fatalf("pair with formatted code: %v", err)
	}
	if _, _, err := store.Pair(code, "Second phone"); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("reusing a code should fail, got %v", err)
	}

	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(contents), token) {
		t.Fatal("the device file must not contain the raw token")
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("device file mode = %v, want 0600", info.Mode().Perm())
	}

	reopened, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	authenticated, ok := reopened.Authenticate(token)
	if !ok || authenticated.ID != device.ID {
		t.Fatalf("token should authenticate after reopening, got %+v %v", authenticated, ok)
	}
	if _, ok := reopened.Authenticate(token + "x"); ok {
		t.Fatal("a wrong token must not authenticate")
	}

	if err := reopened.Revoke(device.ID); err != nil {
		t.Fatal(err)
	}
	if _, ok := reopened.Authenticate(token); ok {
		t.Fatal("a revoked device must not authenticate")
	}
}

func TestPairingCodeRejectedAfterExpiryOrTooManyGuesses(t *testing.T) {
	store, err := Open(filepath.Join(t.TempDir(), "devices.json"))
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	store.now = func() time.Time { return now }

	code, _, _ := store.NewPairingCode()
	now = now.Add(PairingCodeLifetime + time.Second)
	if _, _, err := store.Pair(code, ""); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("expired code should fail, got %v", err)
	}

	code, _, _ = store.NewPairingCode()
	for range maxFailedPairingRuns {
		_, _, _ = store.Pair("WRONGCOD", "")
	}
	if _, _, err := store.Pair(code, ""); !errors.Is(err, ErrInvalidPairingCode) {
		t.Fatalf("code should be burned after repeated guesses, got %v", err)
	}
}
