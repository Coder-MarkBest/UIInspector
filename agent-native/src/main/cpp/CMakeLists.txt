cmake_minimum_required(VERSION 3.22.1)
project(uiinspector_agent LANGUAGES CXX)

add_library(uiinspector_agent SHARED agent.cpp)
target_compile_features(uiinspector_agent PRIVATE cxx_std_17)
target_compile_options(uiinspector_agent PRIVATE -Wall -Wextra -Werror)
target_link_libraries(uiinspector_agent PRIVATE log)

