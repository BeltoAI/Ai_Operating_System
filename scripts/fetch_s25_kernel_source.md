# Fetch the S25's own kernel source (Stage 2 — the trophy)

Samsung is GPLv2-obligated to publish the kernel source for shipped devices. You can download,
compile, and diff the *exact* kernel that runs your S25 — legitimately. You just can't reflash
it onto the locked unit (and we don't try).

## Your target build (from Phase 0)

| | |
|---|---|
| Model | `SM-S931U` |
| Build | `S931USQS1AYBC` |
| Kernel | `6.6.30-android15` |
| Arch | arm64 |

## Get the source

1. Go to the Samsung Open Source Release Center: `https://opensource.samsung.com`.
2. Search model **`SM-S931U`**. Find the entry matching build **`S931USQS1AYBC`**
   (or the closest published `S931U...` kernel package — Samsung bundles by build).
3. Download the `Kernel` tarball (often `SM-S931U_..._Kernel.tar.gz` / `.zip`).
4. Verify and extract:
   ```bash
   sha256sum SM-S931U_*_Kernel.tar.gz       # record the hash
   mkdir s25-kernel && tar xf SM-S931U_*_Kernel.tar.gz -C s25-kernel
   cd s25-kernel
   ls                                       # kernel tree + often a build script
   ```

## Compile it (study build — not for flashing)

Samsung trees usually ship a `build_kernel.sh` or a documented defconfig. General shape:
```bash
export ARCH=arm64
export CROSS_COMPILE=aarch64-linux-gnu-
# Use the model defconfig Samsung ships (name varies — look in arch/arm64/configs/):
make pa1q_defconfig        # example name; confirm the actual *_defconfig in the tree
make -j$(nproc) Image
ls arch/arm64/boot/Image   # the literal S25 kernel, built by you
```
If the tree includes `build_kernel.sh`, prefer it — it sets the exact toolchain/flags Samsung used.

## The trophy diff

```bash
# Compare Samsung's tree to AOSP common to see what Samsung changed:
diff -ruN ~/kernel/common  ~/s25-kernel  | less
```
That diff *is* the interesting part — Samsung's vendor hooks, drivers, and config deltas on top
of mainline. Reading it is real kernel archaeology.

## Honesty box
- **Do not** attempt to flash this (or a modified version) to the S25. Locked verified boot
  rejects it; the only "workarounds" are exploits, which are out of scope.
- Apply the AgentOS patch (`kernel/agentos_patch/`) here too if you want — then boot the result
  in **Cuttlefish**, not on the phone.
- If you can't find the exact `S931USQS1AYBC` package, note which `S931U` build *is* published;
  the kernel deltas between One UI maintenance builds are usually small. Record the hash of
  whatever you download for provenance.
