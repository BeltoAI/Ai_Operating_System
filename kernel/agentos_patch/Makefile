# Out-of-tree build of the AgentOS demo module.
# Point KDIR at your built kernel tree (Cuttlefish common kernel or S25 GPL source).
#
#   make KDIR=~/kernel/common ARCH=arm64 \
#        CROSS_COMPILE=aarch64-linux-gnu- \
#        CC=clang
#
# Then load in Cuttlefish:
#   adb push agentos.ko /data/local/tmp/
#   adb shell su 0 insmod /data/local/tmp/agentos.ko
#   adb shell dmesg | grep agentOS
#   adb shell cat /proc/agentos

obj-m += agentos.o

KDIR ?= /lib/modules/$(shell uname -r)/build

all:
	$(MAKE) -C $(KDIR) M=$(PWD) modules

clean:
	$(MAKE) -C $(KDIR) M=$(PWD) clean
