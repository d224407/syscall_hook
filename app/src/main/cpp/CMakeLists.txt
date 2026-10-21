cmake_minimum_required(VERSION 3.22.1)
project(sclnative C)

add_library(sclnative SHARED supercall.c)
# 16 KB page size compatible
target_link_options(sclnative PRIVATE -Wl,-z,max-page-size=16384)
