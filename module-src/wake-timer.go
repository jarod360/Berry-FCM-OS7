package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const clockBoottimeAlarm = 9
const intervalSeconds = 180

type intervalTimer struct {
	interval syscall.Timespec
	value    syscall.Timespec
}

func waitForAlarm() error {
	fd, _, errno := syscall.Syscall(syscall.SYS_TIMERFD_CREATE, clockBoottimeAlarm, syscall.O_CLOEXEC, 0)
	if errno != 0 {
		return fmt.Errorf("timerfd_create: %w", errno)
	}
	defer syscall.Close(int(fd))

	timer := intervalTimer{value: syscall.Timespec{Sec: intervalSeconds}}
	_, _, errno = syscall.Syscall6(syscall.SYS_TIMERFD_SETTIME, fd, 0, uintptr(unsafe.Pointer(&timer)), 0, 0, 0)
	if errno != 0 {
		return fmt.Errorf("timerfd_settime: %w", errno)
	}
	fmt.Println("armed")
	var event [8]byte
	for {
		n, err := syscall.Read(int(fd), event[:])
		if err == syscall.EINTR {
			continue
		}
		if err != nil {
			return fmt.Errorf("timerfd read: %w", err)
		}
		if n != len(event) {
			return fmt.Errorf("timerfd read: got %d bytes", n)
		}
		return nil
	}
}

func main() {
	if err := waitForAlarm(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
