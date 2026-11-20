cmake_minimum_required(VERSION 3.18)
project(epimetheus_melodica C)
add_library(melodicawire SHARED bridge.c protocol/wire.c protocol/blowfish.c)
