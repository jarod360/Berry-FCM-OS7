#!/system/bin/sh
# KernelSU hot-install supported by newer ksud (official module spec).
# Older managers may ignore this request and stage the module for reboot.
export MODULE_HOT_INSTALL_REQUEST=true
export MODULE_HOT_RUN_SCRIPT=hotinstall.sh
ui_print '- Berry-FCM KSU: no system mounts, no Shizuku required'
ui_print '- Requesting hot install (if supported by your KernelSU version)'
[ ! -f "$MODPATH/bin/wake-timer-arm64" ] || set_perm "$MODPATH/bin/wake-timer-arm64" 0 0 0755
