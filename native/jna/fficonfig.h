/* fficonfig.h for aarch64-linux-android (what libffi's configure would produce with the NDK). */
#define HAVE_ALLOCA_H 1
#define HAVE_AS_CFI_PSEUDO_OP 1
#define HAVE_DLFCN_H 1
#define HAVE_HIDDEN_VISIBILITY_ATTRIBUTE 1
#define HAVE_INTTYPES_H 1
#define HAVE_LONG_DOUBLE 1
#define HAVE_MEMCPY 1
#define HAVE_MEMFD_CREATE 1
#define HAVE_MKOSTEMP 1
#define HAVE_MMAP 1
#define HAVE_MMAP_ANON 1
#define HAVE_MMAP_DEV_ZERO 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_MMAN_H 1
#define HAVE_UNISTD_H 1
#define SIZEOF_DOUBLE 8
#define SIZEOF_LONG_DOUBLE 16
#define SIZEOF_SIZE_T 8
#define STDC_HEADERS 1
#define EH_FRAME_FLAGS "a"
#define LT_OBJDIR ".libs/"
/* Closures: plain anonymous RWX mmap, as HotSpot's JIT already does in this process.
   (FFI_MMAP_EXEC_WRIT would map a file from app data as executable, which SELinux denies.) */
#define FFI_MMAP_EXEC_WRIT 0

#ifdef HAVE_HIDDEN_VISIBILITY_ATTRIBUTE
#ifdef LIBFFI_ASM
#define FFI_HIDDEN(name) .hidden name
#else
#define FFI_HIDDEN __attribute__ ((visibility ("hidden")))
#endif
#else
#ifdef LIBFFI_ASM
#define FFI_HIDDEN(name)
#else
#define FFI_HIDDEN
#endif
#endif
