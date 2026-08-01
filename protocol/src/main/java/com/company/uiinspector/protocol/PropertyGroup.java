package com.company.uiinspector.protocol;

import java.util.Map;

public record PropertyGroup(String name, Map<String, PropertyValue> properties) {}
