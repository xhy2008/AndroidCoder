cmake_minimum_required(VERSION 3.22.1)
project(coderagent_pty C)

add_library(coderagent_pty SHARED pty.c)
target_link_libraries(coderagent_pty log)
