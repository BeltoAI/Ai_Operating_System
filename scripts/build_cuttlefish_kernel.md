# Build & boot a custom kernel in Cuttlefish (Stage 1)

Linux host with KVM required (Ubuntu 22.04+ recommended). macOS cannot run Cuttlefish.

## 0. Host prep
```bash
sudo apt update
sudo apt install -y git curl repo build-essential libncurses-dev flex bison \
                    libssl-dev qemu-kvm
# Confirm virtualization:
egrep -c '(vmx|svm)' /proc/cpuinfo   # must be > 0
```

## 1. Install Cuttlefish host package
```bash
git clone https://github.com/google/android-cuttlefish
cd android-cuttlefish && tools/buildutils/build_packages.sh
sudo dpkg -i ./cuttlefish-base_*.deb || sudo apt -f install -y
sudo usermod -aG kvm,cvdnetwork,render $USER   # then log out/in
```

## 2. Get a Cuttlefish system image (matching API)
Download an `aosp_cf_x86_64_phone` or `aosp_cf_arm64_phone` image set from Android CI
(ci.android.com, branch aosp-main) — `*-img-*.zip` + `cvd-host_package.tar.gz`. Unpack into a
working dir.

## 3. Get + build the common kernel
```bash
mkdir ~/kernel && cd ~/kernel
repo init -u https://android.googlesource.com/kernel/manifest -b common-android15-6.6
repo sync -j$(nproc)

# Baseline build FIRST (prove stock boots before patching):
tools/bazel build //common:kernel_aarch64_dist
tools/bazel run   //common:kernel_aarch64_dist -- --dist_dir=$HOME/kernel/out
ls $HOME/kernel/out/Image            # <- your kernel image
```

## 4. Apply the AgentOS patch and rebuild
Follow `kernel/agentos_patch/README.md` (built-in route), then repeat the bazel build.

## 5. Boot your kernel
```bash
HOME_IMG=~/cf            # where you unpacked the cvd images
cd $HOME_IMG
launch_cvd --kernel_path=$HOME/kernel/out/Image \
           --initramfs_path=$HOME/kernel/out/initramfs.img
adb wait-for-device
adb shell dmesg | grep -i agentOS
adb shell cat /proc/agentos
stop_cvd   # when done
```

Banner + node visible = you booted your own patched kernel. Screenshot it.

## Troubleshooting
- `launch_cvd` permission errors → confirm group membership (`groups`) and KVM access.
- Kernel won't boot → revert to baseline `Image`, confirm stock boots, reintroduce the patch.
- Always keep a known-good baseline image to fall back to.
