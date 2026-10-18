# Builds the LWJGL 3.4.3 JNI libraries Minecraft 26.x needs, for Android:
#   liblwjgl.so (core + libffi), liblwjgl_stb.so, liblwjgl_vma.so
# Sources are the generated JNI glue committed in the upstream LWJGL repository.
cmake_minimum_required(VERSION 3.22)
project(lwjgl-android C CXX ASM)

set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../src)
set(L ${SRC}/lwjgl3/modules/lwjgl)
set(FFI ${SRC}/libffi)
if(NOT JNI_INCLUDE)
  message(FATAL_ERROR "pass -DJNI_INCLUDE=<jdk>/include")
endif()

# ---- libffi (static) ----
set(FFI_GEN ${CMAKE_BINARY_DIR}/ffi-include)
file(READ ${FFI}/include/ffi.h.in FFI_H)
string(REPLACE "@VERSION@" "3.8.0" FFI_H "${FFI_H}")
string(REPLACE "@TARGET@" "AARCH64" FFI_H "${FFI_H}")
string(REPLACE "@HAVE_LONG_DOUBLE@" "1" FFI_H "${FFI_H}")
string(REPLACE "@FFI_EXEC_TRAMPOLINE_TABLE@" "0" FFI_H "${FFI_H}")
string(REPLACE "@FFI_VERSION_STRING@" "3.8.0" FFI_H "${FFI_H}")
string(REPLACE "@FFI_VERSION_NUMBER@" "30800" FFI_H "${FFI_H}")
file(WRITE ${FFI_GEN}/ffi.h "${FFI_H}")
configure_file(${FFI}/src/aarch64/ffitarget.h ${FFI_GEN}/ffitarget.h COPYONLY)
configure_file(${CMAKE_CURRENT_SOURCE_DIR}/fficonfig.h ${FFI_GEN}/fficonfig.h COPYONLY)

add_library(ffi STATIC
  ${FFI}/src/prep_cif.c ${FFI}/src/types.c ${FFI}/src/raw_api.c ${FFI}/src/java_raw_api.c
  ${FFI}/src/closures.c ${FFI}/src/tramp.c
  ${FFI}/src/aarch64/ffi.c ${FFI}/src/aarch64/sysv.S)
target_include_directories(ffi PUBLIC ${FFI_GEN} PRIVATE ${FFI}/include ${FFI}/src/aarch64)
target_compile_options(ffi PRIVATE -fPIC -fvisibility=hidden)

# ---- common LWJGL flags (mirrors config/linux/build.xml) ----
set(LWJGL_DEFS NDEBUG LWJGL_LINUX LWJGL_arm64 _GNU_SOURCE _FILE_OFFSET_BITS=64)
set(LWJGL_INC ${JNI_INCLUDE} ${JNI_INCLUDE}/linux ${L}/core/src/main/c ${L}/core/src/main/c/linux)
set(LWJGL_LDFLAGS -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,--version-script=${SRC}/lwjgl3/config/linux/version.script)

# ---- liblwjgl.so ----
file(GLOB CORE_SRC ${L}/core/src/main/c/*.c ${L}/core/src/generated/c/*.c ${L}/core/src/generated/c/linux/*.c
                   ${L}/core/src/main/c/linux/liburing/*.c)
add_library(lwjgl SHARED ${CORE_SRC})
# Our generated ffi.h must win over the copy vendored in LWJGL's tree.
target_include_directories(lwjgl BEFORE PRIVATE ${FFI_GEN})
target_include_directories(lwjgl PRIVATE ${LWJGL_INC} ${L}/core/src/main/c/linux/liburing ${L}/core/src/main/c/linux/liburing/include)
target_compile_definitions(lwjgl PRIVATE ${LWJGL_DEFS} CONFIG_HAVE_MEMFD_CREATE)
target_compile_options(lwjgl PRIVATE -O3 -std=gnu11 -U_FORTIFY_SOURCE)
target_link_libraries(lwjgl PRIVATE ffi dl m)
target_link_options(lwjgl PRIVATE ${LWJGL_LDFLAGS})

# ---- liblwjgl_stb.so ----
file(GLOB STB_SRC ${L}/stb/src/generated/c/*.c)
add_library(lwjgl_stb SHARED ${STB_SRC})
target_include_directories(lwjgl_stb PRIVATE ${LWJGL_INC})
target_include_directories(lwjgl_stb SYSTEM PRIVATE ${L}/stb/src/main/c)
target_compile_definitions(lwjgl_stb PRIVATE ${LWJGL_DEFS})
target_compile_options(lwjgl_stb PRIVATE -O3 -std=gnu11)
target_link_libraries(lwjgl_stb PRIVATE m)
target_link_options(lwjgl_stb PRIVATE ${LWJGL_LDFLAGS})

# ---- liblwjgl_vma.so ----
file(GLOB VMA_SRC ${L}/vma/src/generated/c/*.cpp)
add_library(lwjgl_vma SHARED ${VMA_SRC})
target_include_directories(lwjgl_vma PRIVATE ${LWJGL_INC} ${L}/vma/src/main/c ${L}/vulkan/src/main/c)
target_compile_definitions(lwjgl_vma PRIVATE ${LWJGL_DEFS})
target_compile_options(lwjgl_vma PRIVATE -O3 -std=gnu++20)
target_link_options(lwjgl_vma PRIVATE ${LWJGL_LDFLAGS} -static-libstdc++)

# ---- liblwjgl_opengl.so ----
# Minecraft loads the OpenGL module at startup even when it renders with Vulkan. The JNI glue
# only calls through function pointers, so no libGL is linked.
file(GLOB GL_SRC ${L}/opengl/src/generated/c/*.c)
list(FILTER GL_SRC EXCLUDE REGEX "org_lwjgl_opengl_WGL\\.c$")
add_library(lwjgl_opengl SHARED ${GL_SRC})
target_include_directories(lwjgl_opengl PRIVATE ${LWJGL_INC} ${L}/opengl/src/main/c)
target_compile_definitions(lwjgl_opengl PRIVATE ${LWJGL_DEFS})
target_compile_options(lwjgl_opengl PRIVATE -O3 -std=gnu11)
target_link_libraries(lwjgl_opengl PRIVATE m)
target_link_options(lwjgl_opengl PRIVATE ${LWJGL_LDFLAGS})
