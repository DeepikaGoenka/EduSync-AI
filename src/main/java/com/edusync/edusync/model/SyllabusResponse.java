package com.edusync.edusync.model;

import java.util.List;

public record SyllabusResponse(
        String courseName,
        List<Unit> units
) {
}