/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * syscall_logger.c (v0.7.0) - KPM tracing su-related syscalls of a PID tree.
 *
 * Logic:
 *  - Tracked set = table of tgids. A process joins if it is listed (add),
 *    matches seed uid, is a child of a tracked pid (getppid check), or is
 *    forked by one (clone after-hook). Exits are removed (exit_group).
 *    Tracking by pid survives su (uid 0), unlike a uid filter.
 *  - Hooks: openat/faccessat/newfstatat (path probes, su-like only unless
 *    verbose), execve/execveat (+argv), connect (+addr), setuid family,
 *    ptrace, mount, clone, exit_group, read(0) (shell stdin, i.e. commands
 *    pushed through a pipe), sendto (DNS query name, UDP:53), write (HTTP
 *    request line + Host, TLS ClientHello SNI, and with `wr 1` the printable
 *    text of any other write, e.g. commands pushed over a pipe fd). "[!]"
 *    marks su-like / uid 0 / remount.
 *  - Output: printk only ("[SCL]" in dmesg); the viewer app reads dmesg, so
 *    there is no separate file sink to manage.
 *  - Heartbeat: the viewer app sends `ping` every few seconds while a target
 *    is open. No `ping` for HEARTBEAT_TIMEOUT seconds -> the module clears
 *    the target itself (seed uid + pid table), so it goes quiet on its own
 *    when the app is not running. `hb 0`/`hb 1` turns this off/on (the app's
 *    "Auto timeout" switch). Needs ktime_get_real_seconds/_seconds in
 *    kallsyms; if neither resolves, `list` reports hb=unsupported and only
 *    an explicit `clear` stops tracking.
 *  - Portability: every kernel symbol is resolved by name at load time with
 *    fallbacks for older/renamed variants (getpid/getppid/kill with and
 *    without the __arm64_sys_ prefix, ktime_get_real_seconds vs
 *    ktime_get_seconds, __arch_copy_from_user vs _copy_from_user vs
 *    copy_from_user). A device missing all variants of a required symbol
 *    fails cleanly at load ("cannot resolve ..."); a missing optional one
 *    (kill, ktime) just turns off pruning/heartbeat, not the whole module.
 *  - Limit: root shells spawned by a su daemon (magiskd) are not children of
 *    the app; only the client-side `su -c ...` argv is visible. TLS SNI is
 *    only the hostname: the request path and headers stay encrypted and are
 *    not visible to a syscall hook.
 *
 * Control without SuperKey (new APatch verifies the manager signature instead):
 *   as root: readlink '/.scl:<token> <cmd>'  -> answer in dmesg "[SCL]ctl <token> <result>"
 * Commands (that channel, or APatch app: KPM tab -> module Control; also load args):
 *   add <pid> | del <pid> | uid <n> | target <uid> | clear | prune | ping
 *   | hb <0|1> | wr <0|1> | verbose <0|1> | list      (a bare number means pid)
 *   clear  = drop all pids AND the seed uid (uid auto-attach would re-add pids).
 *   target = clear, then auto-attach every process of <uid>.
 *   ping   = refresh the heartbeat only (same effect as any other command).
 *   Dead pids (killed by signal, no exit_group) are pruned with kill(pid, 0).
 */

#include <compiler.h>
#include <kpmodule.h>
#include <linux/printk.h>
#include <linux/string.h>
#include <syscall.h>
#include <kputils.h>
#include <ksyms.h>
#include <asm/current.h>

KPM_NAME("syscall_logger");
KPM_VERSION("0.7.0");
KPM_LICENSE("GPL v2");
KPM_AUTHOR("you");
KPM_DESCRIPTION("Trace su-related syscalls of a PID tree (optionally seeded by UID)");

/* arm64 syscall numbers (fallbacks) */
#ifndef __NR_mount
#define __NR_mount 40
#endif
#ifndef __NR_unlinkat
#define __NR_unlinkat 35
#endif
#ifndef __NR_faccessat
#define __NR_faccessat 48
#endif
#ifndef __NR_exit_group
#define __NR_exit_group 94
#endif
#ifndef __NR_ptrace
#define __NR_ptrace 117
#endif
#ifndef __NR_setreuid
#define __NR_setreuid 145
#endif
#ifndef __NR_setuid
#define __NR_setuid 146
#endif
#ifndef __NR_setresuid
#define __NR_setresuid 147
#endif
#ifndef __NR_clone
#define __NR_clone 220
#endif
#ifndef __NR_execveat
#define __NR_execveat 281
#endif
#ifndef __NR_newfstatat
#define __NR_newfstatat 79
#endif
#ifndef __NR_read
#define __NR_read 63
#endif
#ifndef __NR_readlinkat
#define __NR_readlinkat 78
#endif
#ifndef __NR_write
#define __NR_write 64
#endif
#ifndef __NR_sendto
#define __NR_sendto 206
#endif

#define MAX_TRACK  128 /* power of 2 */
#define MAX_ARGS   8
#define SCL_PATH   256
#define SCL_LINE   768
#define CLONE_THREAD_FLAG 0x10000UL

/* state */
static volatile pid_t tracked[MAX_TRACK];
static volatile unsigned int track_next = 0;
static volatile int track_active = 0; /* table non-empty */
static volatile uid_t seed_uid = 0;   /* 0 = off */
static volatile int verbose = 0;      /* 0: log su-like paths only */

/* runtime-resolved kernel funcs */
typedef long (*sys0_t)(void *);
typedef int (*scnprintf_t)(char *, unsigned long, const char *, ...);
static sys0_t kf_getpid = 0;
static sys0_t kf_getppid = 0;
static sys0_t kf_kill_w = 0;               /* __arm64_sys_kill(struct pt_regs *) */
static long (*kf_kill_d)(long, long) = 0;  /* sys_kill(pid, sig), old kernels */
static unsigned long (*kf_cfu)(void *, const void *, unsigned long) = 0;
static unsigned long ucopy(void *to, const void *from, unsigned long n)
{
	return kf_cfu ? kf_cfu(to, from, n) : n;
}
static scnprintf_t kf_scnprintf = 0;
/* heartbeat: app must `ping` periodically or the target is auto-cleared */
static long (*kf_now)(void) = 0; /* seconds, ktime_get_real_seconds/ktime_get_seconds */
static volatile long last_ping = 0;
static volatile int wr_mode = 0;    /* `wr 1`: also log printable write() payloads */
static volatile int hb_enabled = 1; /* `hb 0` disables the auto-clear, kf_now resolved or not */
#define HEARTBEAT_TIMEOUT 20 /* seconds */

/* string helpers */
static int s_eq(const char *a, const char *b)
{
	while (*a && *a == *b) {
		a++;
		b++;
	}
	return *a == *b;
}

static const char *s_base(const char *p)
{
	const char *b = p;

	while (*p) {
		if (*p == '/' && p[1])
			b = p + 1;
		p++;
	}
	return b;
}

static int s_has(const char *h, const char *n)
{
	const char *a, *b;

	if (!*n)
		return 1;
	for (; *h; h++) {
		a = h;
		b = n;
		while (*a && *b && *a == *b) {
			a++;
			b++;
		}
		if (!*b)
			return 1;
	}
	return 0;
}

/* pointer after prefix p, else NULL */
static const char *s_prefix(const char *s, const char *p)
{
	while (*p) {
		if (*s != *p)
			return 0;
		s++;
		p++;
	}
	return s;
}

static int s_append(char *dst, int cap, int len, const char *src)
{
	while (*src && len < cap - 1)
		dst[len++] = *src++;
	dst[len] = 0;
	return len;
}

static unsigned long parse_num(const char *s)
{
	unsigned long v = 0;

	while (*s == ' ')
		s++;
	while (*s >= '0' && *s <= '9') {
		v = v * 10 + (unsigned long)(*s - '0');
		s++;
	}
	return v;
}

/* su-like path: basename su, magisk, supersu, busybox, kernelsu, apatch, xbin */
static int su_like(const char *p)
{
	if (!p || !*p)
		return 0;
	return s_eq(s_base(p), "su") || s_has(p, "magisk") || s_has(p, "supersu") ||
	       s_has(p, "busybox") || s_has(p, "kernelsu") || s_has(p, "apatch") ||
	       s_has(p, "/xbin/su");
}

/* pid table */
static void track_recount(void)
{
	int i, n = 0;

	for (i = 0; i < MAX_TRACK; i++)
		if (tracked[i])
			n++;
	track_active = n > 0;
}

static int track_has(pid_t p)
{
	int i;

	if (p <= 0)
		return 0;
	for (i = 0; i < MAX_TRACK; i++)
		if (tracked[i] == p)
			return 1;
	return 0;
}

/* no memset in KPM: volatile stores cannot be turned into a memset call by GCC */
static void zero_mem(void *p, int n)
{
	volatile unsigned char *v = (volatile unsigned char *)p;

	while (n-- > 0)
		*v++ = 0;
}

/* kill(pid, 0) == -ESRCH -> process is gone (killed by signal, no exit_group) */
static int pid_dead(pid_t p)
{
	long rc;

	if (kf_kill_w) {
		unsigned long fake[48];
		zero_mem(fake, sizeof(fake));
		fake[0] = (unsigned long)p; /* regs[0] = pid, regs[1] = sig 0 */
		rc = kf_kill_w(fake);
	} else if (kf_kill_d) {
		rc = kf_kill_d((long)p, 0);
	} else {
		return 0;
	}
	return rc == -3; /* -ESRCH */
}

static int track_prune(void)
{
	int i, n = 0;

	for (i = 0; i < MAX_TRACK; i++) {
		pid_t p = tracked[i];

		if (p && pid_dead(p)) {
			tracked[i] = 0;
			n++;
		}
	}
	if (n)
		track_recount();
	return n;
}

static void track_add(pid_t p)
{
	int i;

	if (p <= 1 || track_has(p))
		return;
	for (i = 0; i < MAX_TRACK; i++) {
		if (tracked[i] == 0) {
			tracked[i] = p;
			track_recount();
			return;
		}
	}
	/* full: drop dead pids first, then overwrite the oldest slot (ring) */
	if (track_prune()) {
		for (i = 0; i < MAX_TRACK; i++) {
			if (tracked[i] == 0) {
				tracked[i] = p;
				track_recount();
				return;
			}
		}
	}
	i = (int)(track_next++ & (MAX_TRACK - 1));
	tracked[i] = p;
	track_recount();
}

static void track_del(pid_t p)
{
	int i;

	for (i = 0; i < MAX_TRACK; i++)
		if (tracked[i] == p)
			tracked[i] = 0;
	track_recount();
}

static void track_clear(void)
{
	int i;

	for (i = 0; i < MAX_TRACK; i++)
		tracked[i] = 0;
	track_recount();
}

static pid_t cur_tgid(void)
{
	return kf_getpid ? (pid_t)kf_getpid(0) : 0;
}

static pid_t cur_ppid(void)
{
	return kf_getppid ? (pid_t)kf_getppid(0) : 0;
}

/* current task in tracked tree (inherit via parent or seed uid) */
static int is_target(pid_t *out_tgid)
{
	pid_t tgid, ppid;

	if (!track_active && !seed_uid)
		return 0;
	if (kf_now && hb_enabled && last_ping && (track_active || seed_uid) &&
	    (kf_now() - last_ping) > HEARTBEAT_TIMEOUT) {
		seed_uid = 0;
		track_clear();
		pr_info("[SCL] heartbeat timeout: target auto-cleared (viewer app not responding)\n");
		return 0;
	}
	tgid = cur_tgid();
	if (tgid <= 1)
		return 0;
	if (track_has(tgid))
		goto yes;
	if (seed_uid && current_uid() == seed_uid) {
		track_add(tgid);
		goto yes;
	}
	if (track_active) {
		ppid = cur_ppid();
		if (ppid > 1 && track_has(ppid)) {
			track_add(tgid);
			goto yes;
		}
	}
	return 0;
yes:
	*out_tgid = tgid;
	return 1;
}

/* output: kernel log only now (the viewer app reads dmesg) */
static void emit(pid_t pid, const char *ev, const char *detail, int alert)
{
	char line[SCL_LINE];

	kf_scnprintf(line, sizeof(line), "[SCL]%s pid=%d uid=%u %s %s", alert ? "[!]" : "", (int)pid,
		     (unsigned int)current_uid(), ev, detail);
	pr_info("%s\n", line);
}

/* user memory readers */
static void read_path(const char __user *u, char *buf, int cap)
{
	long n;

	buf[0] = 0;
	if (!u)
		return;
	n = compat_strncpy_from_user(buf, u, cap - 1);
	if (n < 0)
		buf[0] = 0;
	buf[cap - 1] = 0; /* always NUL-terminated */
}

static void read_argv(const char __user *const __user *argv, char *out, int cap)
{
	int i, len = 0;

	out[0] = 0;
	if (!argv)
		return;
	for (i = 0; i < MAX_ARGS; i++) {
		unsigned long p = 0;
		char tmp[128];
		long n;

		if (ucopy(&p, argv + i, sizeof(p)))
			break;
		if (!p)
			break;
		n = compat_strncpy_from_user(tmp, (const char __user *)p, sizeof(tmp) - 1);
		if (n < 0)
			break;
		tmp[sizeof(tmp) - 1] = 0;
		if (i)
			len = s_append(out, cap, len, " ");
		len = s_append(out, cap, len, tmp);
		if (len >= cap - 1)
			break;
	}
}

static void fmt_sockaddr(const unsigned char *sa, int len, char *out, int cap)
{
	unsigned int fam;
	unsigned int port;
	int i;

	if (len < 2) {
		kf_scnprintf(out, cap, "addr=?");
		return;
	}
	fam = (unsigned int)sa[0] | ((unsigned int)sa[1] << 8);
	port = ((unsigned int)sa[2] << 8) | sa[3];

	if (fam == 2 && len >= 8) { /* AF_INET */
		kf_scnprintf(out, cap, "inet %u.%u.%u.%u:%u", sa[4], sa[5], sa[6], sa[7], port);
	} else if (fam == 10 && len >= 24) { /* AF_INET6 */
		int mapped = sa[18] == 0xff && sa[19] == 0xff;

		for (i = 8; i < 18 && mapped; i++)
			if (sa[i])
				mapped = 0;
		if (mapped)
			kf_scnprintf(out, cap, "inet6 ::ffff:%u.%u.%u.%u:%u", sa[20], sa[21], sa[22], sa[23], port);
		else
			kf_scnprintf(out, cap, "inet6 [%x:%x:%x:%x:%x:%x:%x:%x]:%u",
				     (sa[8] << 8) | sa[9], (sa[10] << 8) | sa[11], (sa[12] << 8) | sa[13],
				     (sa[14] << 8) | sa[15], (sa[16] << 8) | sa[17], (sa[18] << 8) | sa[19],
				     (sa[20] << 8) | sa[21], (sa[22] << 8) | sa[23], port);
	} else if (fam == 1) { /* AF_UNIX */
		char p[64];
		int j = 0;

		i = 2;
		if (len > 2 && sa[2] == 0) { /* abstract socket */
			p[j++] = '@';
			i = 3;
		}
		for (; i < len && sa[i] && j < 62; i++)
			p[j++] = (char)sa[i];
		p[j] = 0;
		kf_scnprintf(out, cap, "unix %s", p);
	} else {
		kf_scnprintf(out, cap, "family=%u len=%d", fam, len);
	}
}

/* hooks */

/* log only su-like paths unless verbose */
static void probe_path(pid_t pid, const char *ev, int dfd, const char __user *u, unsigned long extra)
{
	char fn[SCL_PATH];
	char d[SCL_PATH + 64];
	int su;

	read_path(u, fn, sizeof(fn));
	su = su_like(fn);
	if (!verbose && !su)
		return;
	kf_scnprintf(d, sizeof(d), "dfd=%d arg=0x%lx path=%s", dfd, extra, fn);
	emit(pid, ev, d, su);
}

/* openat(int dfd, const char *filename, int flags, umode_t mode) */
static void h_openat(hook_fargs4_t *a, void *udata)
{
	pid_t pid;

	if (!is_target(&pid))
		return;
	probe_path(pid, "openat", (int)syscall_argn(a, 0), (const char __user *)syscall_argn(a, 1),
		   (unsigned long)syscall_argn(a, 2));
}

/* faccessat(int dfd, const char *filename, int mode) */
static void h_faccessat(hook_fargs4_t *a, void *udata)
{
	pid_t pid;

	if (!is_target(&pid))
		return;
	probe_path(pid, "faccessat", (int)syscall_argn(a, 0), (const char __user *)syscall_argn(a, 1),
		   (unsigned long)syscall_argn(a, 2));
}

/* newfstatat(int dfd, const char *filename, struct stat *st, int flag) */
static void h_newfstatat(hook_fargs4_t *a, void *udata)
{
	pid_t pid;

	if (!is_target(&pid))
		return;
	probe_path(pid, "newfstatat", (int)syscall_argn(a, 0), (const char __user *)syscall_argn(a, 1),
		   (unsigned long)syscall_argn(a, 3));
}

static void do_exec(pid_t pid, const char *ev, const char __user *upath,
		    const char __user *const __user *uargv)
{
	char fn[SCL_PATH];
	char av[320];
	char d[SCL_PATH + 340];
	int su;

	read_path(upath, fn, sizeof(fn));
	read_argv(uargv, av, sizeof(av));
	su = su_like(fn) || (av[0] && s_eq(s_base(av), "su")) ||
	     (av[0] && s_has(av, "su -") ) || (av[0] && s_has(av, "/su "));
	kf_scnprintf(d, sizeof(d), "file=%s argv=%s", fn, av);
	emit(pid, ev, d, su);
}

/* execve(const char *filename, char *const argv[], char *const envp[]) */
static void h_execve(hook_fargs3_t *a, void *udata)
{
	pid_t pid;

	if (!is_target(&pid))
		return;
	do_exec(pid, "execve", (const char __user *)syscall_argn(a, 0),
		(const char __user *const __user *)syscall_argn(a, 1));
}

/* execveat(int dfd, const char *filename, argv, envp, int flags) */
static void h_execveat(hook_fargs5_t *a, void *udata)
{
	pid_t pid;

	if (!is_target(&pid))
		return;
	do_exec(pid, "execveat", (const char __user *)syscall_argn(a, 1),
		(const char __user *const __user *)syscall_argn(a, 2));
}

/*
 * DNS / HTTP / TLS-SNI: best-effort, read-only payload inspection of what the
 * target process is about to send. These cover what is actually visible at
 * the syscall boundary:
 *   - DNS: the question name of a UDP query sent with sendto() to port 53.
 *   - HTTP: the request line + Host header of a plaintext write().
 *   - HTTPS "endpoint": only the SNI hostname from a TLS ClientHello written
 *     via write(). The request path, headers and body are encrypted and are
 *     NOT visible here; getting those needs a TLS-terminating proxy instead
 *     of a syscall hook.
 */
#define SCL_NET_BUF 600

static int b_starts(const unsigned char *b, int n, const char *p)
{
	int i = 0;

	while (p[i]) {
		if (i >= n || b[i] != (unsigned char)p[i])
			return 0;
		i++;
	}
	return 1;
}

static int is_http_method(const unsigned char *b, int n)
{
	static const char *m[] = { "GET ",     "POST ", "PUT ",   "DELETE ",
				    "OPTIONS ", "HEAD ", "PATCH ", "CONNECT " };
	int i;

	for (i = 0; i < (int)(sizeof(m) / sizeof(m[0])); i++)
		if (b_starts(b, n, m[i]))
			return 1;
	return 0;
}

static int find_sub(const unsigned char *b, int n, const char *needle)
{
	int i, j, nl = 0;

	while (needle[nl])
		nl++;
	for (i = 0; i + nl <= n; i++) {
		int ok = 1;

		for (j = 0; j < nl; j++) {
			if (b[i + j] != (unsigned char)needle[j]) {
				ok = 0;
				break;
			}
		}
		if (ok)
			return i;
	}
	return -1;
}

static void http_summarize(const unsigned char *b, int n, char *out, int cap)
{
	char line1[160];
	char host[128];
	int i, j, eol, hi, k, he;

	eol = n;
	for (i = 0; i + 1 < n; i++) {
		if (b[i] == '\r' && b[i + 1] == '\n') {
			eol = i;
			break;
		}
	}
	j = 0;
	for (i = 0; i < eol && j < (int)sizeof(line1) - 1; i++)
		line1[j++] = (char)b[i];
	line1[j] = 0;

	host[0] = 0;
	hi = find_sub(b, n, "Host:");
	if (hi < 0)
		hi = find_sub(b, n, "host:");
	if (hi >= 0) {
		k = hi + 5;
		while (k < n && b[k] == ' ')
			k++;
		he = k;
		while (he < n && b[he] != '\r' && b[he] != '\n' && he - k < (int)sizeof(host) - 1)
			he++;
		j = 0;
		for (i = k; i < he; i++)
			host[j++] = (char)b[i];
		host[j] = 0;
	}
	kf_scnprintf(out, cap, "%s host=%s", line1, host[0] ? host : "?");
}

/* TLS 1.x ClientHello -> SNI hostname. Bounds-checked, aborts on short/odd input. */
static int tls_sni(const unsigned char *b, int n, char *host, int cap)
{
	int p, session_len, cs_len, cm_len, ext_total, ext_end;

	if (n < 44 || b[0] != 0x16 || b[5] != 0x01)
		return 0;
	p = 43; /* record(5) + hs type/len(4) + client_version(2) + random(32) */
	if (p >= n)
		return 0;
	session_len = b[p];
	p += 1 + session_len;
	if (p + 2 > n)
		return 0;
	cs_len = (b[p] << 8) | b[p + 1];
	p += 2 + cs_len;
	if (p + 1 > n)
		return 0;
	cm_len = b[p];
	p += 1 + cm_len;
	if (p + 2 > n)
		return 0;
	ext_total = (b[p] << 8) | b[p + 1];
	p += 2;
	ext_end = p + ext_total;
	if (ext_end > n)
		ext_end = n;
	while (p + 4 <= ext_end) {
		int etype = (b[p] << 8) | b[p + 1];
		int elen = (b[p + 2] << 8) | b[p + 3];

		p += 4;
		if (p + elen > ext_end)
			break;
		if (etype == 0 && elen >= 5) {
			int sp = p + 2; /* server_name_list_len(2), then type(1) name_len(2) name */

			if (sp + 3 <= p + elen) {
				int name_len = (b[sp + 1] << 8) | b[sp + 2];
				int ns = sp + 3, i;

				if (ns + name_len > p + elen)
					name_len = (p + elen) - ns;
				if (name_len > cap - 1)
					name_len = cap - 1;
				for (i = 0; i < name_len; i++)
					host[i] = (char)b[ns + i];
				host[name_len] = 0;
				return name_len > 0;
			}
		}
		p += elen;
	}
	return 0;
}

static int dns_qname(const unsigned char *b, int n, char *out, int cap)
{
	int p = 12, j = 0, len;

	if (n < 13)
		return 0;
	while (p < n && b[p] != 0) {
		len = b[p++];
		if (len & 0xC0) /* compression pointer: not expected in an outgoing query */
			return 0;
		if (p + len > n)
			return 0;
		if (j && j < cap - 1)
			out[j++] = '.';
		while (len-- > 0 && j < cap - 1)
			out[j++] = (char)b[p++];
	}
	out[j] = 0;
	return j > 0;
}

/* Printable text preview of a write() payload; empty string for binary data. */
static void write_preview(const unsigned char *b, int n, char *out, int cap)
{
	int i, j = 0, printable = 0;
	int m = n > 160 ? 160 : n;

	out[0] = 0;
	for (i = 0; i < m; i++)
		if ((b[i] >= 32 && b[i] < 127) || b[i] == 9 || b[i] == 10 || b[i] == 13)
			printable++;
	if (printable * 10 < m * 8)
		return; /* mostly binary: skip */
	for (i = 0; i < m && j < cap - 4; i++) {
		if (b[i] == 10) {
			out[j++] = 92; /* backslash */
			out[j++] = 110; /* n */
		} else if (b[i] >= 32 && b[i] < 127) {
			out[j++] = (char)b[i];
		} else if (b[i] == 9) {
			out[j++] = 32;
		}
	}
	out[j] = 0;
}

/* sendto(int fd, const void *buf, size_t len, int flags, struct sockaddr *dest_addr, int addrlen) */
static void h_sendto(hook_fargs6_t *a, void *udata)
{
	pid_t pid;
	unsigned char buf[SCL_NET_BUF];
	unsigned char sa[16];
	char name[192];
	char d[220];
	long len = (long)syscall_argn(a, 2);
	int alen = (int)syscall_argn(a, 5);
	unsigned int port;

	if (!is_target(&pid))
		return;
	if (len < 12 || alen < 4 || alen > (int)sizeof(sa))
		return;
	if (ucopy(sa, (const void __user *)syscall_argn(a, 4), (unsigned long)alen))
		return;
	port = ((unsigned int)sa[2] << 8) | sa[3];
	if (port != 53)
		return;
	if (len > (long)sizeof(buf))
		len = (long)sizeof(buf);
	if (ucopy(buf, (const void __user *)syscall_argn(a, 1), (unsigned long)len))
		return;
	if (!dns_qname(buf, (int)len, name, sizeof(name)))
		return;
	kf_scnprintf(d, sizeof(d), "query=%s", name);
	emit(pid, "dns", d, 0);
}

/* write(int fd, const void *buf, size_t count): plaintext HTTP or TLS ClientHello only */
static void h_write(hook_fargs3_t *a, void *udata)
{
	pid_t pid;
	unsigned char buf[SCL_NET_BUF];
	char host[128];
	char line[220];
	char d[320];
	long len = (long)syscall_argn(a, 2);
	int fd = (int)syscall_argn(a, 0);

	if (!is_target(&pid))
		return;
	if (len < 1 || (len < 16 && !wr_mode))
		return;
	if (len > (long)sizeof(buf))
		len = (long)sizeof(buf);
	if (ucopy(buf, (const void __user *)syscall_argn(a, 1), (unsigned long)len))
		return;

	if (is_http_method(buf, (int)len)) {
		http_summarize(buf, (int)len, line, sizeof(line));
		kf_scnprintf(d, sizeof(d), "fd=%d %s", fd, line);
		emit(pid, "http", d, 0);
		return;
	}
	if (tls_sni(buf, (int)len, host, sizeof(host))) {
		kf_scnprintf(d, sizeof(d), "fd=%d sni=%s", fd, host);
		emit(pid, "tls", d, 0);
		return;
	}
	if (wr_mode) {
		write_preview(buf, (int)len, line, sizeof(line));
		if (line[0]) {
			kf_scnprintf(d, sizeof(d), "fd=%d len=%ld data=%s", fd,
				     (long)syscall_argn(a, 2), line);
			emit(pid, "write", d, 0);
		}
	}
}

/* connect(int fd, struct sockaddr *uservaddr, int addrlen) */
static void h_connect(hook_fargs3_t *a, void *udata)
{
	pid_t pid;
	unsigned char sa[112];
	char addr[96];
	char d[160];
	int len = (int)syscall_argn(a, 2);
	int fd = (int)syscall_argn(a, 0);

	if (!is_target(&pid))
		return;
	zero_mem(sa, sizeof(sa));
	if (len > (int)sizeof(sa))
		len = (int)sizeof(sa);
	if (len < 2 || ucopy(sa, (const void __user *)syscall_argn(a, 1), len)) {
		kf_scnprintf(d, sizeof(d), "fd=%d addr=<unreadable>", fd);
	} else {
		fmt_sockaddr(sa, len, addr, sizeof(addr));
		if (!verbose && s_prefix(addr, "unix /dev/socket/"))
			return;
		kf_scnprintf(d, sizeof(d), "fd=%d %s", fd, addr);
	}
	emit(pid, "connect", d, 0);
}

/* setuid(uid) */
static void h_setuid(hook_fargs1_t *a, void *udata)
{
	pid_t pid;
	char d[64];
	unsigned int u = (unsigned int)syscall_argn(a, 0);

	if (!is_target(&pid))
		return;
	kf_scnprintf(d, sizeof(d), "uid=%d", (int)u);
	emit(pid, "setuid", d, u == 0);
}

/* setreuid(ruid, euid) */
static void h_setreuid(hook_fargs2_t *a, void *udata)
{
	pid_t pid;
	char d[64];
	unsigned int r = (unsigned int)syscall_argn(a, 0);
	unsigned int e = (unsigned int)syscall_argn(a, 1);

	if (!is_target(&pid))
		return;
	kf_scnprintf(d, sizeof(d), "ruid=%d euid=%d", (int)r, (int)e);
	emit(pid, "setreuid", d, r == 0 || e == 0);
}

/* setresuid(ruid, euid, suid) */
static void h_setresuid(hook_fargs3_t *a, void *udata)
{
	pid_t pid;
	char d[96];
	unsigned int r = (unsigned int)syscall_argn(a, 0);
	unsigned int e = (unsigned int)syscall_argn(a, 1);
	unsigned int s = (unsigned int)syscall_argn(a, 2);

	if (!is_target(&pid))
		return;
	kf_scnprintf(d, sizeof(d), "ruid=%d euid=%d suid=%d", (int)r, (int)e, (int)s);
	emit(pid, "setresuid", d, r == 0 || e == 0 || s == 0);
}

/* ptrace(long request, long pid, unsigned long addr, unsigned long data) */
static void h_ptrace(hook_fargs4_t *a, void *udata)
{
	pid_t pid;
	char d[96];

	if (!is_target(&pid))
		return;
	kf_scnprintf(d, sizeof(d), "req=%ld target=%ld", (long)syscall_argn(a, 0), (long)syscall_argn(a, 1));
	emit(pid, "ptrace", d, 0);
}

/* mount(char *dev, char *dir, char *type, unsigned long flags, void *data) */
static void h_mount(hook_fargs5_t *a, void *udata)
{
	pid_t pid;
	char dir[SCL_PATH];
	char type[32];
	char d[SCL_PATH + 96];
	unsigned long flags = (unsigned long)syscall_argn(a, 3);

	if (!is_target(&pid))
		return;
	read_path((const char __user *)syscall_argn(a, 1), dir, sizeof(dir));
	read_path((const char __user *)syscall_argn(a, 2), type, sizeof(type));
	kf_scnprintf(d, sizeof(d), "dir=%s type=%s flags=0x%lx", dir, type, flags);
	emit(pid, "mount", d, (flags & 0x20UL) != 0); /* MS_REMOUNT */
}

/* clone after-hook runs in parent; ret = child tid */
static void h_clone_after(hook_fargs5_t *a, void *udata)
{
	pid_t pid;
	char d[48];
	long child = (long)a->ret;
	unsigned long flags = (unsigned long)syscall_argn(a, 0);

	if (child <= 1 || (flags & CLONE_THREAD_FLAG))
		return;
	if (!is_target(&pid))
		return;
	track_add((pid_t)child);
	kf_scnprintf(d, sizeof(d), "child=%ld", child);
	emit(pid, "fork", d, 0);
}

/* exit_group: untrack pid */
static void h_exit_group(hook_fargs1_t *a, void *udata)
{
	pid_t tgid = cur_tgid();
	char d[32];

	if (!track_has(tgid))
		return;
	kf_scnprintf(d, sizeof(d), "code=%d", (int)syscall_argn(a, 0));
	emit(tgid, "exit", d, 0);
	track_del(tgid);
}

/* hook table */
/* read(0, ...) after-hook: commands pushed to a shell through stdin */
static void h_read_after(hook_fargs3_t *a, void *udata)
{
	pid_t pid;
	unsigned char raw[160];
	char d[400];
	long n = (long)a->ret;
	int i, j = 0;

	if ((int)syscall_argn(a, 0) != 0 || n <= 0)
		return;
	if (!is_target(&pid))
		return;
	if (n > (long)sizeof(raw))
		n = (long)sizeof(raw);
	if (ucopy(raw, (const void __user *)syscall_argn(a, 1), n))
		return;
	for (i = 0; i < n && j < (int)sizeof(d) - 4; i++) {
		if (raw[i] == 10) {
			d[j++] = 92;
			d[j++] = 110;
		} else if (raw[i] >= 32 && raw[i] < 127) {
			d[j++] = (char)raw[i];
		} else if (raw[i] != 13) {
			d[j++] = 46;
		}
	}
	d[j] = 0;
	emit(pid, "stdin", d, current_uid() == 0);
}

/* run_cmd() is defined further down */
static void run_cmd(const char *args, char *echo, int cap);

/*
 * Control channel for root, no SuperKey needed:
 *   readlink '/.scl:<token> <cmd>'
 * The answer is printed to the kernel log as "[SCL]ctl <token> <result>".
 */
static void h_readlinkat(hook_fargs4_t *a, void *udata)
{
	char path[SCL_PATH];
	char echo[512];
	char tok[24];
	const char *p;
	int i;

	if (current_uid() != 0)
		return;
	read_path((const char __user *)syscall_argn(a, 1), path, sizeof(path));
	p = s_prefix(path, "/.scl:");
	if (!p)
		return;
	for (i = 0; p[i] && p[i] != ' ' && i < (int)sizeof(tok) - 1; i++)
		tok[i] = p[i];
	tok[i] = 0;
	run_cmd(p + i, echo, sizeof(echo));
	pr_info("[SCL]ctl %s %s\n", tok, echo);
}

struct scl_hook {
	int nr;
	int narg;
	void *before;
	void *after;
	int installed;
};

static struct scl_hook hooks[] = {
	{ __NR_openat, 4, (void *)h_openat, 0, 0 },
	{ __NR_faccessat, 4, (void *)h_faccessat, 0, 0 },
	{ __NR_newfstatat, 4, (void *)h_newfstatat, 0, 0 },
	{ __NR_execve, 3, (void *)h_execve, 0, 0 },
	{ __NR_execveat, 5, (void *)h_execveat, 0, 0 },
	{ __NR_connect, 3, (void *)h_connect, 0, 0 },
	{ __NR_sendto, 6, (void *)h_sendto, 0, 0 },
	{ __NR_write, 3, (void *)h_write, 0, 0 },
	{ __NR_setuid, 1, (void *)h_setuid, 0, 0 },
	{ __NR_setreuid, 2, (void *)h_setreuid, 0, 0 },
	{ __NR_setresuid, 3, (void *)h_setresuid, 0, 0 },
	{ __NR_ptrace, 4, (void *)h_ptrace, 0, 0 },
	{ __NR_mount, 5, (void *)h_mount, 0, 0 },
	{ __NR_clone, 5, 0, (void *)h_clone_after, 0 },
	{ __NR_read, 3, 0, (void *)h_read_after, 0 },
	{ __NR_readlinkat, 4, (void *)h_readlinkat, 0, 0 },
	{ __NR_exit_group, 1, (void *)h_exit_group, 0, 0 },
};
#define NHOOKS ((int)(sizeof(hooks) / sizeof(hooks[0])))

static void unhook_all(void)
{
	int i;

	for (i = 0; i < NHOOKS; i++) {
		if (hooks[i].installed) {
			unhook_syscalln(hooks[i].nr, hooks[i].before, hooks[i].after);
			hooks[i].installed = 0;
		}
	}
}

/* commands (init args + control) */
static void run_cmd(const char *args, char *echo, int cap)
{
	const char *r;
	char tmp[96];
	int len = 0, i, n = 0;

	if (kf_now)
		last_ping = kf_now();

	echo[0] = 0;
	while (args && *args == ' ')
		args++;

	if (!args || !*args || s_prefix(args, "ping")) {
		/* status-only / heartbeat refresh, handled above */
		/* status only */
	} else if (args[0] >= '0' && args[0] <= '9') {
		track_add((pid_t)parse_num(args));
	} else if ((r = s_prefix(args, "add "))) {
		track_add((pid_t)parse_num(r));
	} else if ((r = s_prefix(args, "del "))) {
		track_del((pid_t)parse_num(r));
	} else if (s_prefix(args, "clear")) {
		seed_uid = 0; /* stop uid auto-attach first, otherwise it re-adds pids */
		track_clear();
	} else if ((r = s_prefix(args, "target "))) {
		seed_uid = 0;
		track_clear();
		seed_uid = (uid_t)parse_num(r);
	} else if (s_prefix(args, "prune")) {
		track_prune();
	} else if ((r = s_prefix(args, "hb "))) {
		hb_enabled = parse_num(r) ? 1 : 0;
	} else if ((r = s_prefix(args, "wr "))) {
		wr_mode = parse_num(r) ? 1 : 0;
	} else if ((r = s_prefix(args, "uid "))) {
		seed_uid = (uid_t)parse_num(r);
	} else if ((r = s_prefix(args, "verbose "))) {
		verbose = parse_num(r) ? 1 : 0;
	} else if (!s_prefix(args, "list")) {
		len = s_append(echo, cap, len, "unknown cmd; ");
	}

	track_prune();
	for (i = 0; i < MAX_TRACK; i++)
		if (tracked[i])
			n++;
	kf_scnprintf(tmp, sizeof(tmp), "uid=%u verbose=%d n=%d hb=%s wr=%s pids:",
		     (unsigned int)seed_uid, (int)verbose, n,
		     !kf_now ? "unsupported" : (hb_enabled ? "on" : "off"), wr_mode ? "on" : "off");
	len = s_append(echo, cap, len, tmp);
	for (i = 0; i < MAX_TRACK; i++) {
		pid_t p = tracked[i];

		if (!p)
			continue;
		kf_scnprintf(tmp, sizeof(tmp), " %d", (int)p);
		if (len + (int)strlen(tmp) >= cap - 1) {
			len = s_append(echo, cap, len, " ...");
			break;
		}
		len = s_append(echo, cap, len, tmp);
	}
}

static unsigned long lookup3(const char *a, const char *b, const char *c)
{
	unsigned long p = kallsyms_lookup_name(a);

	if (!p && b)
		p = kallsyms_lookup_name(b);
	if (!p && c)
		p = kallsyms_lookup_name(c);
	return p;
}

/* module lifecycle */
static long syscall_logger_init(const char *args, const char *event, void *__user reserved)
{
	char echo[512];
	int i, failed = 0;

	pr_info("syscall_logger init, args: %s\n", args ? args : "");

	kf_scnprintf = (scnprintf_t)kallsyms_lookup_name("scnprintf");
	kf_getpid = (sys0_t)lookup3("__arm64_sys_getpid", "sys_getpid", "SyS_getpid");
	kf_getppid = (sys0_t)lookup3("__arm64_sys_getppid", "sys_getppid", "SyS_getppid");
	kf_kill_w = (sys0_t)kallsyms_lookup_name("__arm64_sys_kill");
	if (!kf_kill_w)
		kf_kill_d = (void *)lookup3("sys_kill", "SyS_kill", 0);
	kf_cfu = (void *)lookup3("__arch_copy_from_user", "_copy_from_user", "copy_from_user");
	if (!kf_scnprintf || !kf_getpid || !kf_getppid || !kf_cfu) {
		pr_err("syscall_logger: cannot resolve scnprintf/getpid/getppid\n");
		return -1;
	}

	/* heartbeat optional: missing symbol just disables the auto-clear safety net */
	kf_now = (void *)lookup3("ktime_get_real_seconds", "ktime_get_seconds", 0);
	if (kf_now)
		last_ping = kf_now();

	run_cmd(args, echo, sizeof(echo));

	for (i = 0; i < NHOOKS; i++) {
		hook_err_t err = hook_syscalln(hooks[i].nr, hooks[i].narg, hooks[i].before,
					       hooks[i].after, 0);
		if (err) {
			pr_err("syscall_logger: hook nr=%d failed: %d\n", hooks[i].nr, (int)err);
			failed = 1;
			break;
		}
		hooks[i].installed = 1;
	}
	if (failed) {
		unhook_all();
		return -1;
	}

	pr_info("syscall_logger ready: %s\n", echo);
	return 0;
}

/* ctl0: same commands as the load args */
static long syscall_logger_control(const char *args, char *__user out_msg, int outlen)
{
	char echo[512];
	int n;

	run_cmd(args, echo, sizeof(echo));
	n = (int)strlen(echo) + 1;
	if (n > outlen)
		n = outlen;
	if (n <= 0 || !out_msg)
		return 0;
	echo[n - 1] = 0;
	compat_copy_to_user(out_msg, echo, n);
	return 0;
}

static long syscall_logger_exit(void *__user reserved)
{
	/* stop events first, then unhook */
	seed_uid = 0;
	last_ping = 0;
	track_clear();
	unhook_all();
	pr_info("syscall_logger exit\n");
	return 0;
}

KPM_INIT(syscall_logger_init);
KPM_CTL0(syscall_logger_control);
KPM_EXIT(syscall_logger_exit);
