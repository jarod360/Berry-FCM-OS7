package main

import (
	"encoding/binary"
	"fmt"
	"os"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

const clockBoottimeAlarm = 9
const clockBoottime = 7
const clockMonotonic = 1
const intervalSeconds = 180

type intervalTimer struct {
	interval syscall.Timespec
	value    syscall.Timespec
}

func clockSeconds(id uintptr) (float64, error) {
	var stamp syscall.Timespec
	_, _, errno := syscall.Syscall(syscall.SYS_CLOCK_GETTIME, id, uintptr(unsafe.Pointer(&stamp)), 0)
	if errno != 0 {
		return 0, errno
	}
	return float64(stamp.Sec) + float64(stamp.Nsec)/1e9, nil
}

func waitForAlarm() error {
	fd, _, errno := syscall.Syscall(syscall.SYS_TIMERFD_CREATE, clockBoottimeAlarm, syscall.O_CLOEXEC, 0)
	if errno != 0 {
		return fmt.Errorf("timerfd_create: %w", errno)
	}
	defer syscall.Close(int(fd))

	startBoot, bootErr := clockSeconds(clockBoottime)
	startActive, activeErr := clockSeconds(clockMonotonic)
	timer := intervalTimer{value: syscall.Timespec{Sec: intervalSeconds}}
	_, _, errno = syscall.Syscall6(syscall.SYS_TIMERFD_SETTIME, fd, 0, uintptr(unsafe.Pointer(&timer)), 0, 0, 0)
	if errno != 0 {
		return fmt.Errorf("timerfd_settime: %w", errno)
	}
	var armed intervalTimer
	_, _, errno = syscall.Syscall(syscall.SYS_TIMERFD_GETTIME, fd, uintptr(unsafe.Pointer(&armed)), 0)
	remaining := "unknown"
	if errno == 0 {
		remaining = fmt.Sprintf("%.3f", float64(armed.value.Sec)+float64(armed.value.Nsec)/1e9)
	}
	fmt.Printf("[%s] wake_timer_armed pid=%d delay_s=%d remaining_s=%s\n",
		time.Now().UTC().Format(time.RFC3339), os.Getpid(), intervalSeconds, remaining)
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
		break
	}
	endBoot, endBootErr := clockSeconds(clockBoottime)
	endActive, endActiveErr := clockSeconds(clockMonotonic)
	elapsed, suspended, late := "unknown", "unknown", "unknown"
	if bootErr == nil && endBootErr == nil {
		delta := endBoot - startBoot
		elapsed = fmt.Sprintf("%.3f", delta)
		late = fmt.Sprintf("%.3f", max(0, delta-intervalSeconds))
		if activeErr == nil && endActiveErr == nil {
			suspended = fmt.Sprintf("%.3f", max(0, delta-(endActive-startActive)))
		}
	}
	fmt.Printf("[%s] wake_timer_fired pid=%d expirations=%d elapsed_s=%s suspend_s=%s late_s=%s\n",
		time.Now().UTC().Format(time.RFC3339), os.Getpid(), binary.LittleEndian.Uint64(event[:]), elapsed, suspended, late)
	if reason, err := os.ReadFile("/sys/kernel/wakeup_reasons/last_resume_reason"); err == nil {
		fmt.Printf("wake_timer_last_kernel_reason pid=%d reason=%q\n", os.Getpid(), strings.TrimSpace(string(reason)))
	}
	return nil
}

func main() {
	if err := waitForAlarm(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
