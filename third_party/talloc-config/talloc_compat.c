/*
 * The one function talloc.c needs from Samba's lib/replace on Android (bionic has no
 * memset_explicit before API 34). Copied from talloc-2.5.0/lib/replace/replace.c, LGPL-3.0+.
 */
#include <string.h>

void *rep_memset_explicit(void *block, int c, size_t size)
{
	void *ptr = memset(block, c, size);
	/* See http://llvm.org/bugs/show_bug.cgi?id=15495 */
	__asm__ volatile("" : : "g"(block) : "memory");
	return ptr;
}
