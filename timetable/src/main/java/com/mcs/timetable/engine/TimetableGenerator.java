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

            List<Integer> generalSoftwareLabs = loadGeneralSoftwareLabs(conn);

            List<AllocationTask> tasks = loadAllocations(conn, semesterTerm);
            tasks.sort((a, b) -> Integer.compare(b.labBlocksNeeded, a.labBlocksNeeded));

            String insertSQL = "INSERT INTO timetable_schedule " +
                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, lab_group_tag) "
                    +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {

                // PHASE 1: SCHEDULE 3-CONSECUTIVE-SLOT LABS
                for (AllocationTask task : tasks) {
                    if (task.labBlocksNeeded > 0) {
                        List<Integer> candidateLabs = new ArrayList<>(
                                loadRoomsByType(conn, "LAB", task.requiredLabSpecialty));
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

    // ========================================================================
    // 1. MID-SEMESTER INSTRUCTOR REPLACEMENT (WITH AUTO-CLASH RESOLUTION)
    // ========================================================================
    public String replaceInstructor(String semesterTerm, int allocationId, int newInstructorId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            int sectionId = 0;
            String courseTitle = "";
            String allocSql = "SELECT ca.section_id, c.course_title FROM course_allocations ca " +
                    "JOIN courses c ON ca.course_id = c.course_id WHERE ca.allocation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(allocSql)) {
                ps.setInt(1, allocationId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    sectionId = rs.getInt("section_id");
                    courseTitle = rs.getString("course_title");
                } else {
                    return "ERROR: Allocation record not found.";
                }
            }

            try (PreparedStatement updAlloc = conn.prepareStatement(
                    "UPDATE course_allocations SET instructor_id = ? WHERE allocation_id = ?")) {
                updAlloc.setInt(1, newInstructorId);
                updAlloc.setInt(2, allocationId);
                updAlloc.executeUpdate();
            }

            List<int[]> nonLabSlots = new ArrayList<>();
            List<String> nonLabTypes = new ArrayList<>();
            List<int[]> labSlots = new ArrayList<>();

            String fetchSched = "SELECT schedule_id, slot_id, room_id, session_type, lab_engineer_id " +
                    "FROM timetable_schedule WHERE allocation_id = ? AND semester_term = ? ORDER BY slot_id";
            try (PreparedStatement ps = conn.prepareStatement(fetchSched)) {
                ps.setInt(1, allocationId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    int rId = rs.getInt("room_id");
                    if (rs.wasNull())
                        rId = 0;
                    int leId = rs.getInt("lab_engineer_id");
                    if (rs.wasNull())
                        leId = 0;
                    String type = rs.getString("session_type");

                    if ("LAB".equals(type)) {
                        labSlots.add(new int[] { rs.getInt("schedule_id"), rs.getInt("slot_id"), rId, leId });
                    } else {
                        nonLabSlots.add(new int[] { rs.getInt("schedule_id"), rs.getInt("slot_id"), rId });
                        nonLabTypes.add(type);
                    }
                }
            }

            int directTransferred = 0;
            int rescheduledCount = 0;
            List<String> unresolved = new ArrayList<>();

            for (int i = 0; i < nonLabSlots.size(); i++) {
                int schedId = nonLabSlots.get(i)[0];
                int slotId = nonLabSlots.get(i)[1];
                int roomId = nonLabSlots.get(i)[2];
                String type = nonLabTypes.get(i);

                if (isPersonFreeInDB(conn, semesterTerm, slotId, newInstructorId)) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE timetable_schedule SET instructor_id = ? WHERE schedule_id = ?")) {
                        upd.setInt(1, newInstructorId);
                        upd.setInt(2, schedId);
                        upd.executeUpdate();
                        directTransferred++;
                    }
                } else {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                        del.setInt(1, schedId);
                        del.executeUpdate();
                    }
                    String movedSlot = relocateSingleSession(conn, semesterTerm, allocationId, sectionId,
                            newInstructorId, roomId, type, true);
                    if (movedSlot != null)
                        rescheduledCount++;
                    else
                        unresolved.add("Could not auto-relocate " + type + " slot");
                }
            }

            if (!labSlots.isEmpty()) {
                boolean allLabSlotsFree = true;
                for (int[] ls : labSlots) {
                    if (!isPersonFreeInDB(conn, semesterTerm, ls[1], newInstructorId)) {
                        allLabSlotsFree = false;
                        break;
                    }
                }

                if (allLabSlotsFree) {
                    for (int[] ls : labSlots) {
                        try (PreparedStatement upd = conn.prepareStatement(
                                "UPDATE timetable_schedule SET instructor_id = ? WHERE schedule_id = ?")) {
                            upd.setInt(1, newInstructorId);
                            upd.setInt(2, ls[0]);
                            upd.executeUpdate();
                            directTransferred++;
                        }
                    }
                } else {
                    int oldLabRoomId = labSlots.get(0)[2];
                    int leId = labSlots.get(0)[3];
                    for (int[] ls : labSlots) {
                        try (PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                            del.setInt(1, ls[0]);
                            del.executeUpdate();
                        }
                    }
                    boolean labMoved = relocateLabBlockInDB(conn, semesterTerm, allocationId, sectionId,
                            newInstructorId, leId, oldLabRoomId);
                    if (labMoved)
                        rescheduledCount += labSlots.size();
                    else
                        unresolved.add("Could not auto-relocate 3-slot LAB block");
                }
            }

            conn.commit();
            String summary = "Instructor Replacement Complete for " + courseTitle + ":\n" +
                    "• Direct Clash-Free Transfers: " + directTransferred + " slots\n" +
                    "• Auto-Rescheduled (due to new instructor's clash): " + rescheduledCount + " slots";
            if (!unresolved.isEmpty())
                summary += "\n• Warnings: " + String.join(", ", unresolved);
            return summary;

        } catch (SQLException e) {
            e.printStackTrace();
            return "DATABASE ERROR: " + e.getMessage();
        }
    }

    // ========================================================================
    // 2. MID-SEMESTER LAB ENGINEER (LE) REPLACEMENT
    // ========================================================================
    public String replaceLabEngineer(String semesterTerm, int allocationId, int newLeId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            int sectionId = 0, instructorId = 0;
            String courseTitle = "";
            String allocSql = "SELECT ca.section_id, ca.instructor_id, c.course_title FROM course_allocations ca " +
                    "JOIN courses c ON ca.course_id = c.course_id WHERE ca.allocation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(allocSql)) {
                ps.setInt(1, allocationId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    sectionId = rs.getInt("section_id");
                    instructorId = rs.getInt("instructor_id");
                    courseTitle = rs.getString("course_title");
                } else {
                    return "ERROR: Allocation record not found.";
                }
            }

            // Update master allocation
            try (PreparedStatement updAlloc = conn.prepareStatement(
                    "UPDATE course_allocations SET lab_engineer_id = ? WHERE allocation_id = ?")) {
                updAlloc.setInt(1, newLeId);
                updAlloc.setInt(2, allocationId);
                updAlloc.executeUpdate();
            }

            // Fetch existing LAB slots
            List<int[]> labSlots = new ArrayList<>(); // [schedule_id, slot_id, room_id]
            String fetchLabs = "SELECT schedule_id, slot_id, room_id FROM timetable_schedule " +
                    "WHERE allocation_id = ? AND semester_term = ? AND session_type = 'LAB' ORDER BY slot_id";
            try (PreparedStatement ps = conn.prepareStatement(fetchLabs)) {
                ps.setInt(1, allocationId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    labSlots.add(new int[] { rs.getInt("schedule_id"), rs.getInt("slot_id"), rs.getInt("room_id") });
                }
            }

            if (labSlots.isEmpty()) {
                conn.commit();
                return "Lab Engineer updated in allocation record (No scheduled LAB slots existed for " + courseTitle
                        + ").";
            }

            boolean allFree = true;
            for (int[] ls : labSlots) {
                if (!isPersonFreeInDB(conn, semesterTerm, ls[1], newLeId)) {
                    allFree = false;
                    break;
                }
            }

            if (allFree) {
                for (int[] ls : labSlots) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE timetable_schedule SET lab_engineer_id = ? WHERE schedule_id = ?")) {
                        upd.setInt(1, newLeId);
                        upd.setInt(2, ls[0]);
                        upd.executeUpdate();
                    }
                }
                conn.commit();
                return "SUCCESS! Lab Engineer replaced directly across all 3 lab slots for " + courseTitle
                        + " with ZERO clashes.";
            } else {
                int oldRoomId = labSlots.get(0)[2];
                for (int[] ls : labSlots) {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                        del.setInt(1, ls[0]);
                        del.executeUpdate();
                    }
                }
                boolean moved = relocateLabBlockInDB(conn, semesterTerm, allocationId, sectionId, instructorId, newLeId,
                        oldRoomId);
                conn.commit();
                if (moved) {
                    return "SUCCESS! New Lab Engineer had a clash in the old slot, so the 3-slot Lab block for "
                            + courseTitle + " was automatically moved to a new conflict-free slot.";
                } else {
                    return "WARNING: Could not find 3 consecutive free slots for the new Lab Engineer and Instructor together.";
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
            return "DATABASE ERROR: " + e.getMessage();
        }
    }

    // ========================================================================
    // 3. RESCHEDULE SINGLE CLASS FOR A SECTION & TEACHER (WITH CLASH & DUPLICATE
    // CHECK)
    // ========================================================================
    public String rescheduleSingleClass(String semesterTerm, int scheduleId) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            conn.setAutoCommit(false);

            int allocId = 0, sectionId = 0, instId = 0, roomId = 0;
            String oldDay = "", sessionType = "", courseTitle = "", secName = "";
            int oldPeriod = 0;

            String fetchSql = """
                        SELECT sch.allocation_id, sch.section_id, sch.instructor_id, IFNULL(sch.room_id, 0) AS r_id,
                               sch.session_type, ts.day_of_week, ts.period_no, c.course_title,
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS sec_lbl
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        WHERE sch.schedule_id = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(fetchSql)) {
                ps.setInt(1, scheduleId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    allocId = rs.getInt("allocation_id");
                    sectionId = rs.getInt("section_id");
                    instId = rs.getInt("instructor_id");
                    roomId = rs.getInt("r_id");
                    sessionType = rs.getString("session_type");
                    oldDay = rs.getString("day_of_week");
                    oldPeriod = rs.getInt("period_no");
                    courseTitle = rs.getString("course_title");
                    secName = rs.getString("sec_lbl");
                } else {
                    return "ERROR: Selected class record not found.";
                }
            }

            if ("LAB".equals(sessionType)) {
                return "NOTE: Selected session is a 3-hour LAB block. Please use Lab Engineer / Instructor replacement or full regeneration for 3-hour labs.";
            }

            // Check if there are ALREADY rescheduled classes in this section (for
            // transparency/warning)
            List<String> existingRescheduledInfo = new ArrayList<>();
            String checkExistingResched = """
                        SELECT ts.day_of_week, ts.period_no, c.course_title, CONCAT(f.rank_title, ' ', f.full_name) AS tname
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON sch.instructor_id = f.faculty_id
                        WHERE sch.section_id = ? AND sch.semester_term = ? AND sch.is_rescheduled = TRUE
                    """;
            try (PreparedStatement ps = conn.prepareStatement(checkExistingResched)) {
                ps.setInt(1, sectionId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    existingRescheduledInfo.add(rs.getString("day_of_week") + " Period " + rs.getInt("period_no") +
                            " (" + rs.getString("course_title") + " - " + rs.getString("tname") + ")");
                }
            }

            // Remove old slot temporarily
            try (PreparedStatement del = conn
                    .prepareStatement("DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                del.setInt(1, scheduleId);
                del.executeUpdate();
            }

            // Find new clash-free slot (ensuring section doesn't already have another
            // teacher/rescheduled class there)
            String newSlotDesc = relocateSingleSession(conn, semesterTerm, allocId, sectionId, instId, roomId,
                    sessionType, true);
            if (newSlotDesc == null) {
                conn.rollback();
                return "FAILED: No mutual free slot found where both " + secName
                        + " and the instructor are free without clashing with another teacher.";
            }

            conn.commit();

            StringBuilder sb = new StringBuilder();
            sb.append("RESCHEDULED SUCCESSFULLY!\n");
            sb.append("• Section: ").append(secName).append(" | Subject: ").append(courseTitle).append(" (")
                    .append(sessionType).append(")\n");
            sb.append("• Moved From: ").append(oldDay).append(" Period ").append(oldPeriod).append("\n");
            sb.append("• Moved To: ").append(newSlotDesc).append(" (Marked as [RESCHEDULED])\n");
            sb.append("• Verified: No other teacher is assigned to ").append(secName).append(" in this slot.");

            if (!existingRescheduledInfo.isEmpty()) {
                sb.append("\n\n⚠️ Notice (Other Previously Rescheduled Classes in ").append(secName).append("):\n");
                for (String info : existingRescheduledInfo) {
                    sb.append("   - ").append(info).append("\n");
                }
            }
            return sb.toString();

        } catch (SQLException e) {
            e.printStackTrace();
            return "DATABASE ERROR: " + e.getMessage();
        }
    }

    private boolean isPersonFreeInDB(Connection conn, String term, int slotId, int personId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND slot_id = ? " +
                "AND (instructor_id = ? OR lab_engineer_id = ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            ps.setInt(2, slotId);
            ps.setInt(3, personId);
            ps.setInt(4, personId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private String relocateSingleSession(Connection conn, String term, int allocId, int sectionId,
            int instId, int preferredRoomId, String type, boolean markRescheduled) throws SQLException {
        List<TimeSlot> validSlots = loadValidMorningSlots(conn);
        Collections.shuffle(validSlots);

        for (TimeSlot s : validSlots) {
            // Strict check: Neither the section (any teacher/rescheduled class) nor this
            // teacher can be busy in slot s
            String checkConflict = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND slot_id = ? " +
                    "AND (section_id = ? OR instructor_id = ? OR lab_engineer_id = ?)";
            try (PreparedStatement ps = conn.prepareStatement(checkConflict)) {
                ps.setString(1, term);
                ps.setInt(2, s.slotId);
                ps.setInt(3, sectionId);
                ps.setInt(4, instId);
                ps.setInt(5, instId);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt(1) > 0)
                    continue;
            }

            Integer chosenRoom = null;
            String roomName = "Office/STI";
            if (!"STI".equals(type)) {
                String roomQuery = """
                            SELECT r.room_id, r.room_name FROM rooms r
                            WHERE r.room_type IN ('CLASSROOM', 'LECTURE_HALL')
                            AND r.room_id NOT IN (
                                SELECT room_id FROM timetable_schedule
                                WHERE semester_term = ? AND slot_id = ? AND room_id IS NOT NULL
                            ) LIMIT 1
                        """;
                try (PreparedStatement ps = conn.prepareStatement(roomQuery)) {
                    ps.setString(1, term);
                    ps.setInt(2, s.slotId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next()) {
                        chosenRoom = rs.getInt("room_id");
                        roomName = rs.getString("room_name");
                    } else {
                        continue;
                    }
                }
            }

            String ins = "INSERT INTO timetable_schedule " +
                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, is_rescheduled, reschedule_note) "
                    +
                    "VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                ps.setString(1, term);
                ps.setInt(2, allocId);
                ps.setInt(3, s.slotId);
                ps.setInt(4, sectionId);
                ps.setInt(5, instId);
                if (chosenRoom != null)
                    ps.setInt(6, chosenRoom);
                else
                    ps.setNull(6, Types.INTEGER);
                ps.setString(7, type);
                ps.setBoolean(8, markRescheduled);
                ps.setString(9, markRescheduled ? "Rescheduled Slot" : null);
                ps.executeUpdate();
                return s.dayOfWeek + " Period " + s.periodNo + " (" + s.startTime + "-" + s.endTime + ") in "
                        + roomName;
            }
        }
        return null;
    }

    private boolean relocateLabBlockInDB(Connection conn, String term, int allocId, int sectionId,
            int instId, int leId, int oldLabRoomId) throws SQLException {
        Map<String, List<TimeSlot>> slotsByDay = groupSlotsByDay(loadValidMorningSlots(conn));
        List<Integer> labs = loadGeneralSoftwareLabs(conn);
        if (!labs.contains(oldLabRoomId) && oldLabRoomId > 0)
            labs.add(0, oldLabRoomId);

        for (List<TimeSlot> daySlots : slotsByDay.values()) {
            for (int i = 0; i <= daySlots.size() - 3; i++) {
                TimeSlot s1 = daySlots.get(i), s2 = daySlots.get(i + 1), s3 = daySlots.get(i + 2);
                if (s2.periodNo != s1.periodNo + 1 || s3.periodNo != s2.periodNo + 1)
                    continue;

                boolean peopleOk = true;
                for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                    String q = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND slot_id = ? " +
                            "AND (section_id = ? OR instructor_id = ? OR lab_engineer_id = ? OR instructor_id = ? OR lab_engineer_id = ?)";
                    try (PreparedStatement ps = conn.prepareStatement(q)) {
                        ps.setString(1, term);
                        ps.setInt(2, s.slotId);
                        ps.setInt(3, sectionId);
                        ps.setInt(4, instId);
                        ps.setInt(5, instId);
                        ps.setInt(6, leId);
                        ps.setInt(7, leId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next() && rs.getInt(1) > 0) {
                            peopleOk = false;
                            break;
                        }
                    }
                }
                if (!peopleOk)
                    continue;

                for (int labId : labs) {
                    String rq = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND room_id = ? AND slot_id IN (?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(rq)) {
                        ps.setString(1, term);
                        ps.setInt(2, labId);
                        ps.setInt(3, s1.slotId);
                        ps.setInt(4, s2.slotId);
                        ps.setInt(5, s3.slotId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next() && rs.getInt(1) == 0) {
                            String ins = "INSERT INTO timetable_schedule " +
                                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, lab_group_tag, is_rescheduled) "
                                    +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'LAB', ?, TRUE)";
                            for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                                try (PreparedStatement ips = conn.prepareStatement(ins)) {
                                    ips.setString(1, term);
                                    ips.setInt(2, allocId);
                                    ips.setInt(3, s.slotId);
                                    ips.setInt(4, sectionId);
                                    ips.setInt(5, instId);
                                    if (leId > 0)
                                        ips.setInt(6, leId);
                                    else
                                        ips.setNull(6, Types.INTEGER);
                                    ips.setInt(7, labId);
                                    ips.setString(8, "LAB_" + allocId);
                                    ips.executeUpdate();
                                }
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    // --- Core Generation Helpers ---
    private boolean scheduleLabBlock(PreparedStatement stmt, String term, AllocationTask task,
            Map<String, List<TimeSlot>> slotsByDay, List<Integer> candidateLabs) throws SQLException {
        List<String> days = new ArrayList<>(slotsByDay.keySet());
        Collections.shuffle(days);

        for (String day : days) {
            List<TimeSlot> daySlots = slotsByDay.get(day);
            for (int i = 0; i <= daySlots.size() - 3; i++) {
                TimeSlot s1 = daySlots.get(i), s2 = daySlots.get(i + 1), s3 = daySlots.get(i + 2);
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