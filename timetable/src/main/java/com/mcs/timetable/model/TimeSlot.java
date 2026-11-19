package com.mcs.timetable.model;

public class TimeSlot {
    public int slotId;
    public String dayOfWeek; // MON, TUE, WED, THU, FRI
    public int periodNo; // 1 to 7
    public String startTime;
    public String endTime;
    public boolean isBlocked;
}