// SPDX-License-Identifier: GPL-2.0
/*
 * AgentOS demo kernel module — proof you built & booted your own kernel.
 *
 * Adds a /proc/agentos read-only node and prints a boot banner to dmesg.
 * This touches NO security control: no SELinux change, no verified-boot change,
 * no privilege escalation. It's a "hello, kernel" you can point to and say
 * "I patched the Linux kernel and booted it."
 *
 * Build paths:
 *   - As a built-in: drop into the kernel tree (see README in this dir) and
 *     select CONFIG_AGENTOS_PROC=y.
 *   - As an out-of-tree module: use the provided Makefile against your built tree.
 */

#include <linux/init.h>
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/proc_fs.h>
#include <linux/seq_file.h>
#include <linux/version.h>

#define AGENTOS_TAG "agentOS"

static int agentos_show(struct seq_file *m, void *v)
{
	seq_printf(m,
		"agentOS kernel node\n"
		"built-by: owner-device dev\n"
		"kernel: %d.%d.%d\n"
		"status: awake\n",
		(LINUX_VERSION_CODE >> 16) & 0xff,
		(LINUX_VERSION_CODE >> 8) & 0xff,
		LINUX_VERSION_CODE & 0xff);
	return 0;
}

static int agentos_open(struct inode *inode, struct file *file)
{
	return single_open(file, agentos_show, NULL);
}

static const struct proc_ops agentos_proc_ops = {
	.proc_open    = agentos_open,
	.proc_read    = seq_read,
	.proc_lseek   = seq_lseek,
	.proc_release = single_release,
};

static int __init agentos_init(void)
{
	pr_info("%s: waking up — custom kernel online\n", AGENTOS_TAG);
	if (!proc_create("agentos", 0444, NULL, &agentos_proc_ops)) {
		pr_err("%s: failed to create /proc/agentos\n", AGENTOS_TAG);
		return -ENOMEM;
	}
	pr_info("%s: /proc/agentos ready\n", AGENTOS_TAG);
	return 0;
}

static void __exit agentos_exit(void)
{
	remove_proc_entry("agentos", NULL);
	pr_info("%s: sleeping\n", AGENTOS_TAG);
}

module_init(agentos_init);
module_exit(agentos_exit);

MODULE_LICENSE("GPL v2");
MODULE_AUTHOR("AgentOS");
MODULE_DESCRIPTION("AgentOS proof-of-custom-kernel /proc node and boot banner");
