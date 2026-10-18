cmake_minimum_required(VERSION 3.22)
project(prismdroid C)

# Prebuilt natives produced by native/build-natives.sh (SDL3, LWJGL, OpenAL, ...).
set(JNILIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(SDL_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/src/SDL/include)

add_library(SDL3 SHARED IMPORTED)
set_target_properties(SDL3 PROPERTIES IMPORTED_LOCATION ${JNILIBS}/libSDL3.so)

# SDLActivity loads this as the "main" library and calls SDL_main() on its SDL thread.
add_library(prismdroid SHARED launcher.c)
target_include_directories(prismdroid PRIVATE ${SDL_INCLUDE})
target_link_libraries(prismdroid SDL3 log dl android)
target_link_options(prismdroid PRIVATE -Wl,-z,max-page-size=16384)

# Vulkan loader wrapper: emulates VK_KHR_push_descriptor where the driver lacks it.
add_library(vkshim SHARED vkshim.c)
target_link_libraries(vkshim log dl)
target_compile_options(vkshim PRIVATE -fvisibility=hidden -O2)
target_link_options(vkshim PRIVATE -Wl,-z,max-page-size=16384)
