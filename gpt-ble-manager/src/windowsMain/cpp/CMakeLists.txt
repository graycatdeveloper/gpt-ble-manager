cmake_minimum_required(VERSION 3.20)
project(gpt-ble-windows LANGUAGES CXX)

# JNI comes from the JDK; WinRT comes from the Windows SDK of the selected Visual Studio toolchain.
# https://cmake.org/cmake/help/latest/module/FindJNI.html
find_package(JNI REQUIRED)

add_library(gpt-ble-windows SHARED
    src/adapter/Adapter.cpp
    src/bluetooth/BluetoothUtils.cpp
    src/gatt/AttributeOperations.cpp
    src/gatt/ConnectionOperations.cpp
    src/gatt/Discovery.cpp
    src/gatt/Subscriptions.cpp
    src/jni/JniRuntime.cpp
    src/jni/NativeBridge.cpp
    src/pairing/Pairing.cpp
    src/runtime/WinrtRuntime.cpp
    src/scan/AdvertisementParser.cpp
    src/scan/KnownDevices.cpp
    src/scan/Scanner.cpp
    src/state/Connection.cpp
    src/state/Manager.cpp
    src/state/Registry.cpp
    src/adapter/Adapter.hpp
    src/bluetooth/BluetoothUtils.hpp
    src/gatt/AttributeOperations.hpp
    src/gatt/ConnectionOperations.hpp
    src/gatt/Discovery.hpp
    src/gatt/Subscriptions.hpp
    src/jni/JniRuntime.hpp
    src/pairing/Pairing.hpp
    src/runtime/WinrtRuntime.hpp
    src/scan/AdvertisementParser.hpp
    src/scan/KnownDevices.hpp
    src/scan/Scanner.hpp
    src/state/Connection.hpp
    src/state/Manager.hpp
    src/state/Registry.hpp
)

# All headers are internal: JNI exports remain the library's public ABI.
target_include_directories(gpt-ble-windows PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${CMAKE_CURRENT_SOURCE_DIR}/src"
)
target_compile_features(gpt-ble-windows PRIVATE cxx_std_20)
target_compile_options(gpt-ble-windows PRIVATE /EHsc /utf-8 /W4)
target_compile_definitions(gpt-ble-windows PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
target_link_libraries(gpt-ble-windows PRIVATE WindowsApp)

# Static MSVC runtime: DLL consumers do not need a separate VC++ Redistributable.
# https://cmake.org/cmake/help/latest/prop_tgt/MSVC_RUNTIME_LIBRARY.html
set_property(TARGET gpt-ble-windows PROPERTY
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
)

# Separate data format checks that require neither a BLE device nor a JVM.
# Disabled by default: a regular Gradle build still produces only the DLL.
option(GPT_BLE_BUILD_NATIVE_TESTS "Build hardware-independent native contract tests" OFF)
if(GPT_BLE_BUILD_NATIVE_TESTS)
    enable_testing()
    add_executable(gpt-ble-native-tests
        tests/NativeContractsTest.cpp
        src/bluetooth/BluetoothUtils.cpp
        src/runtime/WinrtRuntime.cpp
        src/scan/AdvertisementParser.cpp
    )
    target_include_directories(gpt-ble-native-tests PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/src")
    target_compile_features(gpt-ble-native-tests PRIVATE cxx_std_20)
    target_compile_options(gpt-ble-native-tests PRIVATE /EHsc /utf-8 /W4)
    target_compile_definitions(gpt-ble-native-tests PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN)
    target_link_libraries(gpt-ble-native-tests PRIVATE WindowsApp)
    set_property(TARGET gpt-ble-native-tests PROPERTY
        MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>"
    )
    add_test(NAME native-contracts COMMAND gpt-ble-native-tests)
endif()
