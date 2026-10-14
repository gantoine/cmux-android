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
	cellarBinary := filepath.Join(prefix, "Cellar", "cmux-relay", "0.1.0", "bin", "cmux-relay")
	if err := os.MkdirAll(filepath.Dir(cellarBinary), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(cellarBinary, []byte("binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(prefix, "bin", "cmux-relay")
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(cellarBinary, link); err != nil {
		t.Fatal(err)
	}

	if got := preferStableLink(cellarBinary, link); got != link {
		t.Fatalf("a Homebrew install must record %s, not the versioned Cellar path; got %s", link, got)
	}

	other := filepath.Join(prefix, "elsewhere", "cmux-relay")
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
