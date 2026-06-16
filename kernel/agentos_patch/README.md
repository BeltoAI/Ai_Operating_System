# AgentOS demo kernel patch

A harmless proof-of-custom-kernel: a boot banner in `dmesg` and a read-only `/proc/agentos`
node. It changes **no** security control (no SELinux, no verified boot, no privilege paths).
Its only job is to let you boot a kernel that is provably *yours*.

## Two ways to use it

### A) Out-of-tree module (quickest to demo in Cuttlefish)
```bash
make KDIR=~/kernel/common ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- CC=clang
adb push agentos.ko /data/local/tmp/
adb shell su 0 insmod /data/local/tmp/agentos.ko
adb shell dmesg | grep agentOS
adb shell cat /proc/agentos
```

### B) Built-in (true "I patched the kernel" build)
1. Copy `agentos.c` into the kernel tree, e.g. `drivers/agentos/agentos.c`.
2. Add `drivers/agentos/Kconfig`:
   ```
   config AGENTOS_PROC
       bool "AgentOS proof /proc node"
       default y
       help
         Adds /proc/agentos and a boot banner. Demonstration only.
   ```
3. Add `drivers/agentos/Makefile`:
   ```
   obj-$(CONFIG_AGENTOS_PROC) += agentos.o
   ```
4. In `drivers/Kconfig` add `source "drivers/agentos/Kconfig"`, and in `drivers/Makefile` add
   `obj-$(CONFIG_AGENTOS_PROC) += agentos/`.
5. Enable `CONFIG_AGENTOS_PROC=y` in your defconfig/fragment, rebuild, boot in Cuttlefish.

Built-in route = the banner prints during boot and the node exists from init — that's the
version you screenshot.
