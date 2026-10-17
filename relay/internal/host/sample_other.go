//go:build !darwin

package host

import (
	"context"
	"errors"
)

func Sample(context.Context) (Usage, error) {
	return Usage{}, errors.New("host usage is only measured on macOS")
}
