package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestInstallRecordsTheLinkThatSurvivesUpgrades(t *testing.T) {
	prefix, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	cellarBinary := filepath.Join(prefix, "Cellar", "muxer", "0.1.0", "bin", "muxer")
	if err := os.MkdirAll(filepath.Dir(cellarBinary), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cellarBinary, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(prefix, "bin", "muxer")
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(cellarBinary, link); err != nil {
		t.Fatal(err)
	}

	if got := preferStableLink(cellarBinary, link); got != link {
		t.Fatalf("a Homebrew install must record %s, not the versioned Cellar path; got %s", link, got)
	}

	other := filepath.Join(prefix, "elsewhere", "muxer")
	if err := os.MkdirAll(filepath.Dir(other), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(other, []byte("other"), 0o755); err != nil {
		t.Fatal(err)
	}
	if got := preferStableLink(other, link); got != other {
		t.Fatalf("a different binary on PATH must not replace the one being installed; got %s", got)
	}
	if got := preferStableLink(cellarBinary, ""); got != cellarBinary {
		t.Fatalf("without a PATH entry the running binary is used; got %s", got)
	}
}

func TestFixPossessiveDropsTheSecondSAfterAnS(t *testing.T) {
	cases := map[string]string{
		"Georges's MacBook Pro":      "Georges' MacBook Pro",
		"Georges\u2019s MacBook Pro": "Georges\u2019 MacBook Pro",
		"Anna's MacBook Air":         "Anna's MacBook Air",
		"M4":                         "M4",
	}
	for input, want := range cases {
		if got := fixPossessive(input); got != want {
			t.Errorf("fixPossessive(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestInstallKeepsPhonesPairedWithTheOldRelay(t *testing.T) {
	root := t.TempDir()
	legacyDir := filepath.Join(root, "cmux-android-relay")
	dir := filepath.Join(root, "muxer")
	if err := os.MkdirAll(legacyDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacyDir, "devices.json"), []byte("[]"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := adoptLegacyStateDir(legacyDir, dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "devices.json")); err != nil {
		t.Fatalf("paired phones must move to the new state folder: %v", err)
	}

	if err := os.MkdirAll(legacyDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacyDir, "devices.json"), []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := adoptLegacyStateDir(legacyDir, dir); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(filepath.Join(dir, "devices.json")); string(got) != "[]" {
		t.Fatalf("an existing state folder must not be replaced; got %q", got)
	}
}
