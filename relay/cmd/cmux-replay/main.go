package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"

	"github.com/gantoine/muxer/relay/internal/cmuxrecord"
)

func main() {
	recordingPath := flag.String("recording", "", "recording made by the e2e suite")
	socketPath := flag.String("socket", "", "unix socket to serve the recorded cmux on")
	control := flag.String("control", "127.0.0.1:8790", "address of the HTTP control API (GET /calls, POST /emit?name=)")
	flag.Parse()
	if *recordingPath == "" || *socketPath == "" {
		fmt.Fprintln(os.Stderr, "usage: cmux-replay -recording FILE -socket PATH [-control ADDR]")
		os.Exit(2)
	}
	recording, err := cmuxrecord.Load(*recordingPath)
	if err != nil {
		log.Fatalf("read the recording: %v", err)
	}
	replayer := cmuxrecord.NewReplayer(recording)
	_ = os.Remove(*socketPath)
	listener, err := net.Listen("unix", *socketPath)
	if err != nil {
		log.Fatal(err)
	}
	go func() {
		log.Fatal(http.ListenAndServe(*control, replayer.ControlHandler()))
	}()
	log.Printf("replaying %d calls and %d events on %s, control on %s", len(recording.Calls), len(recording.Events), *socketPath, *control)
	log.Fatal(replayer.Serve(listener))
}
