package com.edusync.edusync.model;

import java.util.List;

public record Unit(
        String unitName,
        List<Topic> topics
) {
}