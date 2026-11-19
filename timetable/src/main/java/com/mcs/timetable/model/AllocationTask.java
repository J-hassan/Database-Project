package com.mcs.timetable.model;

public class AllocationTask {
    public int allocationId;
    public int sectionId;
    public String sectionLabel; // e.g., BESE-31 D
    public int courseId;
    public String courseTitle;
    public int instructorId;
    public Integer labEngineerId; // Nullable
    public int theorySlotsNeeded;
    public int labBlocksNeeded; // Each block = 3 consecutive slots
    public int stiSlotsNeeded;
    public String requiredLabSpecialty;
}