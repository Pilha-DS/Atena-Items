cmake_minimum_required(VERSION 3.20)

project(atena-settings VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# This machine has no KF6 meta package (nothing provides KF6Config.cmake), so
# each framework is found by its own package name.
find_package(Qt6 REQUIRED COMPONENTS Widgets)
find_package(KF6KCMUtils REQUIRED)
find_package(KF6CoreAddons REQUIRED)

add_executable(atena-settings
    src/main.cpp
)

target_link_libraries(atena-settings PRIVATE
    Qt6::Widgets
    KF6::KCMUtils
    KF6::CoreAddons
)

install(TARGETS atena-settings RUNTIME DESTINATION bin)
