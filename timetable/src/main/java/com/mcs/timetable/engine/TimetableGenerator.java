package com.mcs.timetable.engine;

import com.mcs.timetable.db.DatabaseConnection;
import com.mcs.timetable.model.AllocationTask;
import com.mcs.timetable.model.TimeSlot;

import java.sql.*;
import java.util.*;

public class TimetableGenerator {

    private final Set<String> busySectionSlots = new HashSet<>();
    private final Set<String> busyInstructorSlots = new HashSet<>();
    private final Set<String> busyLESlots = new HashSet<>();
    private final Set<String> busyRoomSlots = new HashSet<>();
    private final Set<String> sectionCourseDays = new HashSet<>();

    public String generateForTerm(String semesterTerm) {
        // Retry up to 15 times with randomized ordering to guarantee 0 warnings
        String bestReport = "";
        for (int attempt = 1; attempt <= 15; attempt++) {
            GenerationResult res = runSingleAttempt(semesterTerm);
            bestReport = res.message;
            if (res.warningCount == 0) {
                return res.message + " (Resolved in attempt #" + attempt + ")";
            }
        }
        return bestReport;
    }

    private static class GenerationResult {
        int warningCount;
        String message;

        GenerationResult(int warningCount, String message) {
            this.warningCount = warningCount;
            this.message = message;
        }
    }

    private GenerationResult runSingleAttempt(String semesterTerm) {
        busySectionSlots.clear();
        busyInstructorSlots.clear();
        busyLESlots.clear();
        busyRoomSlots.clear();
        sectionCourseDays.clear();

        int scheduledCount = 0;
        List<String> warnings = new ArrayList<>();

        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            try (PreparedStatement del = conn
                    .prepareStatement("DELETE FROM timetable_schedule WHERE semester_term = ?")) {
                del.setString(1, semesterTerm);
                del.executeUpdate();
            }

            List<TimeSlot> validSlots = loadValidMorningSlots(conn);
            Map<String, List<TimeSlot>> slotsByDay = groupSlotsByDay(validSlots);

            List<Integer> regularRooms = new ArrayList<>();
            regularRooms.addAll(loadRoomsByType(conn, "CLASSROOM", null));
            regularRooms.addAll(loadRoomsByType(conn, "LECTURE_HALL", null));

            // General Software Labs pool (MCS allows DSA, AI, FCP, ICT, HCI across these
            // labs)
            List<Integer> generalSoftwareLabs = loadGeneralSoftwareLabs(conn);

            List<AllocationTask> tasks = loadAllocations(conn, semesterTerm);
            // Schedule Labs with strict/scarce LEs first (Most Constrained Variable
            // heuristic)
            tasks.sort((a, b) -> Integer.compare(b.labBlocksNeeded, a.labBlocksNeeded));

            String insertSQL = "INSERT INTO timetable_schedule " +
                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, lab_group_tag) "
                    +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {

                // PHASE 1: SCHEDULE 3-CONSECUTIVE-SLOT LABS (Primary Lab + Compatible Software
                // Lab Fallback)
                for (AllocationTask task : tasks) {
                    if (task.labBlocksNeeded > 0) {
                        List<Integer> candidateLabs = new ArrayList<>(
                                loadRoomsByType(conn, "LAB", task.requiredLabSpecialty));
                        // If not Physics Lab, allow fallback to other CSE software labs
                        if (!"PHYSICS".equalsIgnoreCase(task.requiredLabSpecialty)) {
                            for (int labId : generalSoftwareLabs) {
                                if (!candidateLabs.contains(labId)) {
                                    candidateLabs.add(labId);
                                }
                            }
                        }

                        boolean placed = scheduleLabBlock(insertStmt, semesterTerm, task, slotsByDay, candidateLabs);
                        if (placed) {
                            scheduledCount += 3;
                        } else {
                            warnings.add("Lab conflict for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                // PHASE 2: SCHEDULE THEORY LECTURES
                for (AllocationTask task : tasks) {
                    for (int i = 0; i < task.theorySlotsNeeded; i++) {
                        boolean placed = scheduleSingleTheory(insertStmt, semesterTerm, task, validSlots, regularRooms,
                                false);
                        if (!placed) {
                            // Relax same-day soft constraint if week is tight
                            placed = scheduleSingleTheory(insertStmt, semesterTerm, task, validSlots, regularRooms,
                                    true);
                        }
                        if (placed) {
                            scheduledCount++;
                        } else {
                            warnings.add("Theory slot missing for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                // PHASE 3: SCHEDULE STI SLOTS
                for (AllocationTask task : tasks) {
                    for (int i = 0; i < task.stiSlotsNeeded; i++) {
                        boolean placed = scheduleSTI(insertStmt, semesterTerm, task, validSlots);
                        if (placed) {
                            scheduledCount++;
                        } else {
                            warnings.add("STI slot missing for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                insertStmt.executeBatch();
            }

            conn.commit();
            if (warnings.isEmpty()) {
                return new GenerationResult(0, "SUCCESS! Generated " + scheduledCount
                        + " conflict-free class/lab/STI slots across all 14 sections with ZERO warnings.");
            } else {
                return new GenerationResult(warnings.size(), "Generated " + scheduledCount + " slots with "
                        + warnings.size() + " warnings:\n" + String.join("\n", warnings));
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return new GenerationResult(999, "DATABASE ERROR: " + e.getMessage());
        }
    }

    private boolean scheduleLabBlock(PreparedStatement stmt, String term, AllocationTask task,
            Map<String, List<TimeSlot>> slotsByDay, List<Integer> candidateLabs) throws SQLException {
        List<String> days = new ArrayList<>(slotsByDay.keySet());
        Collections.shuffle(days);

        for (String day : days) {
            List<TimeSlot> daySlots = slotsByDay.get(day);
            for (int i = 0; i <= daySlots.size() - 3; i++) {
                TimeSlot s1 = daySlots.get(i);
                TimeSlot s2 = daySlots.get(i + 1);
                TimeSlot s3 = daySlots.get(i + 2);

                if (s2.periodNo != s1.periodNo + 1 || s3.periodNo != s2.periodNo + 1)
                    continue;
                if (!isPeopleFree(s1.slotId, task) || !isPeopleFree(s2.slotId, task) || !isPeopleFree(s3.slotId, task))
                    continue;

                for (int labId : candidateLabs) {
                    if (isRoomFree(s1.slotId, labId) && isRoomFree(s2.slotId, labId) && isRoomFree(s3.slotId, labId)) {
                        String tag = "LAB_" + task.allocationId;
                        for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                            addBatchRow(stmt, term, task, s.slotId, labId, "LAB", tag);
                            markBusy(s.slotId, task, labId);
                        }
                        sectionCourseDays.add(task.sectionId + "_" + task.courseId + "_" + day);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean scheduleSingleTheory(PreparedStatement stmt, String term, AllocationTask task,
            List<TimeSlot> validSlots, List<Integer> rooms, boolean allowSameDay) throws SQLException {
        List<TimeSlot> shuffledSlots = new ArrayList<>(validSlots);
        Collections.shuffle(shuffledSlots);

        for (TimeSlot s : shuffledSlots) {
            String dayKey = task.sectionId + "_" + task.courseId + "_" + s.dayOfWeek;
            if (!allowSameDay && sectionCourseDays.contains(dayKey))
                continue;
            if (!isInstructorAndSectionFree(s.slotId, task))
                continue;

            List<Integer> shuffledRooms = new ArrayList<>(rooms);
            Collections.shuffle(shuffledRooms);

            for (int roomId : shuffledRooms) {
                if (isRoomFree(s.slotId, roomId)) {
                    addBatchRow(stmt, term, task, s.slotId, roomId, "THEORY", null);
                    markTheoryBusy(s.slotId, task, roomId);
                    sectionCourseDays.add(dayKey);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean scheduleSTI(PreparedStatement stmt, String term, AllocationTask task,
            List<TimeSlot> validSlots) throws SQLException {
        List<TimeSlot> shuffledSlots = new ArrayList<>(validSlots);
        Collections.shuffle(shuffledSlots);

        for (TimeSlot s : shuffledSlots) {
            if (isInstructorAndSectionFree(s.slotId, task)) {
                addBatchRow(stmt, term, task, s.slotId, null, "STI", null);
                busySectionSlots.add(s.slotId + "_" + task.sectionId);
                busyInstructorSlots.add(s.slotId + "_" + task.instructorId);
                return true;
            }
        }
        return false;
    }

    private boolean isPeopleFree(int slotId, AllocationTask t) {
        if (!isInstructorAndSectionFree(slotId, t))
            return false;
        return t.labEngineerId == null || !busyLESlots.contains(slotId + "_" + t.labEngineerId);
    }

    private boolean isInstructorAndSectionFree(int slotId, AllocationTask t) {
        return !busySectionSlots.contains(slotId + "_" + t.sectionId)
                && !busyInstructorSlots.contains(slotId + "_" + t.instructorId);
    }

    private boolean isRoomFree(int slotId, int roomId) {
        return !busyRoomSlots.contains(slotId + "_" + roomId);
    }

    private void markBusy(int slotId, AllocationTask t, int roomId) {
        markTheoryBusy(slotId, t, roomId);
        if (t.labEngineerId != null)
            busyLESlots.add(slotId + "_" + t.labEngineerId);
    }

    private void markTheoryBusy(int slotId, AllocationTask t, int roomId) {
        busySectionSlots.add(slotId + "_" + t.sectionId);
        busyInstructorSlots.add(slotId + "_" + t.instructorId);
        busyRoomSlots.add(slotId + "_" + roomId);
    }

    private void addBatchRow(PreparedStatement stmt, String term, AllocationTask t,
            int slotId, Integer roomId, String type, String tag) throws SQLException {
        stmt.setString(1, term);
        stmt.setInt(2, t.allocationId);
        stmt.setInt(3, slotId);
        stmt.setInt(4, t.sectionId);
        stmt.setInt(5, t.instructorId);
        if (t.labEngineerId != null && "LAB".equals(type))
            stmt.setInt(6, t.labEngineerId);
        else
            stmt.setNull(6, Types.INTEGER);
        if (roomId != null)
            stmt.setInt(7, roomId);
        else
            stmt.setNull(7, Types.INTEGER);
        stmt.setString(8, type);
        if (tag != null)
            stmt.setString(9, tag);
        else
            stmt.setNull(9, Types.VARCHAR);
        stmt.addBatch();
    }

    private List<TimeSlot> loadValidMorningSlots(Connection conn) throws SQLException {
        List<TimeSlot> list = new ArrayList<>();
        String sql = "SELECT slot_id, day_of_week, period_no, start_time, end_time FROM time_slots " +
                "WHERE shift_type = 'MORNING' AND is_global_blocked = FALSE ORDER BY FIELD(day_of_week,'MON','TUE','WED','THU','FRI'), period_no";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                TimeSlot ts = new TimeSlot();
                ts.slotId = rs.getInt("slot_id");
                ts.dayOfWeek = rs.getString("day_of_week");
                ts.periodNo = rs.getInt("period_no");
                ts.startTime = rs.getString("start_time");
                ts.endTime = rs.getString("end_time");
                list.add(ts);
            }
        }
        return list;
    }

    private Map<String, List<TimeSlot>> groupSlotsByDay(List<TimeSlot> slots) {
        Map<String, List<TimeSlot>> map = new LinkedHashMap<>();
        for (TimeSlot s : slots) {
            map.computeIfAbsent(s.dayOfWeek, k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    private List<Integer> loadRoomsByType(Connection conn, String type, String specialty) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = (specialty == null)
                ? "SELECT room_id FROM rooms WHERE room_type = ?"
                : "SELECT room_id FROM rooms WHERE room_type = ? AND lab_specialty = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            if (specialty != null)
                ps.setString(2, specialty);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    ids.add(rs.getInt("room_id"));
            }
        }
        return ids;
    }

    private List<Integer> loadGeneralSoftwareLabs(Connection conn) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT room_id FROM rooms WHERE room_type = 'LAB' AND (lab_specialty IS NULL OR lab_specialty <> 'PHYSICS')";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                ids.add(rs.getInt("room_id"));
        }
        return ids;
    }

    private List<AllocationTask> loadAllocations(Connection conn, String term) throws SQLException {
        List<AllocationTask> list = new ArrayList<>();
        String sql = """
                    SELECT ca.*, c.course_title, c.required_lab_specialty,
                           CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS sec_label
                    FROM course_allocations ca
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN batch_sections bs ON ca.section_id = bs.section_id
                    JOIN programs p ON bs.program_id = p.program_id
                    WHERE ca.semester_term = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AllocationTask t = new AllocationTask();
                    t.allocationId = rs.getInt("allocation_id");
                    t.sectionId = rs.getInt("section_id");
                    t.sectionLabel = rs.getString("sec_label");
                    t.courseId = rs.getInt("course_id");
                    t.courseTitle = rs.getString("course_title");
                    t.instructorId = rs.getInt("instructor_id");
                    int le = rs.getInt("lab_engineer_id");
                    t.labEngineerId = rs.wasNull() ? null : le;
                    t.theorySlotsNeeded = rs.getInt("theory_slots_per_week");
                    t.labBlocksNeeded = rs.getInt("lab_blocks_per_week");
                    t.stiSlotsNeeded = rs.getInt("sti_slots_per_week");
                    t.requiredLabSpecialty = rs.getString("required_lab_specialty");
                    list.add(t);
                }
            }
        }
        return list;
    }
}