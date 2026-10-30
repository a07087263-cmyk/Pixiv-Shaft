# SVT-AV1 4.2.0 finds the NDK's LLVM tools by absolute path, then throws that
# path away and puts only "llvm-ar"/"llvm-ranlib" in Ninja rules. Android's
# build PATH does not contain those bare names. Keep the paths CMake found.
if(NOT DEFINED SVT_AV1_SOURCE_DIR)
    message(FATAL_ERROR "SVT_AV1_SOURCE_DIR is required")
endif()
set(file "${SVT_AV1_SOURCE_DIR}/CMakeLists.txt")
file(READ "${file}" contents)
set(original "${contents}")
string(REPLACE "SET (CMAKE_LINKER  \"llvm-ld\")"
       "SET (CMAKE_LINKER  \"\${LLVM_LD_EXE}\")" contents "${contents}")
string(REPLACE "SET (CMAKE_AR      \"llvm-ar\")"
       "SET (CMAKE_AR      \"\${LLVM_AR_EXE}\")" contents "${contents}")
string(REPLACE "SET (CMAKE_RANLIB  \"llvm-ranlib\")"
       "SET (CMAKE_RANLIB  \"\${LLVM_RANLIB_EXE}\")" contents "${contents}")
if(contents STREQUAL original)
    message(FATAL_ERROR "SVT-AV1 LLVM tool override block was not found; review the upstream patch")
endif()
file(WRITE "${file}" "${contents}")
