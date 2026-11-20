package com.mcs.timetable;

import com.mcs.timetable.db.DatabaseConnection;
import com.mcs.timetable.engine.TimetableGenerator;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MainApp extends Application {

    // Tab 1 Controls
    private final ComboBox<String> sectionDropdown = new ComboBox<>();
    private final Map<String, Integer> sectionMap = new HashMap<>();
    private final GridPane timetableGrid = new GridPane();
    private final TextArea statusArea = new TextArea(
            "Ready. Click 'Generate Conflict-Free Timetable' to schedule Fall-2026.");

    // Tab 2 Tables
    private final TableView<ObservableList<String>> facultyTable = new TableView<>();
    private final TableView<ObservableList<String>> roomTable = new TableView<>();

    // Tab 3 Allocation Controls
    private final ComboBox<String> allocSectionBox = new ComboBox<>();
    private final ComboBox<String> allocCourseBox = new ComboBox<>();
    private final ComboBox<String> allocInstBox = new ComboBox<>();
    private final ComboBox<String> allocLEBox = new ComboBox<>();
    private final Map<String, Integer> courseMap = new HashMap<>();
    private final Map<String, Integer> facultyMap = new HashMap<>();
    private final Map<String, Integer> leMap = new HashMap<>();
    private final TableView<ObservableList<String>> allocTable = new TableView<>();

    // Tab 4 Lookup Controls
    private final ComboBox<String> lookupTypeBox = new ComboBox<>();
    private final ComboBox<String> lookupEntityBox = new ComboBox<>();
    private final TableView<ObservableList<String>> lookupTable = new TableView<>();

    // Tab 5 Free Instructors Controls
    private final ComboBox<String> freeDayBox = new ComboBox<>();
    private final ComboBox<String> freePeriodBox = new ComboBox<>();
    private final TableView<ObservableList<String>> freeFacultyTable = new TableView<>();

    // Tab 6 Reschedule Single Class Controls
    private final ComboBox<String> reschedSectionBox = new ComboBox<>();
    private final ComboBox<String> reschedTeacherBox = new ComboBox<>();
    private final TableView<ObservableList<String>> reschedClassesTable = new TableView<>();
    private final TextArea reschedLogArea = new TextArea();

    @Override
    public void start(Stage stage) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab tab1 = new Tab("📅 Timetable & Generator", buildTimetableTab());
        Tab tab2 = new Tab("👨‍🏫 Faculty & Rooms", buildMasterDataTab());
        Tab tab3 = new Tab("📚 Allocations & Replace", buildAllocationsTab());
        Tab tab4 = new Tab("🔍 Teacher / Room Schedule", buildLookupTab());
        Tab tab5 = new Tab("🟢 Free Instructors (Meetings)", buildFreeInstructorsTab());
        Tab tab6 = new Tab("🔁 Reschedule Class", buildRescheduleTab());

        tabPane.getTabs().addAll(tab1, tab2, tab3, tab4, tab5, tab6);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
            refreshAllDropdownsAndTables();
        });

        refreshAllDropdownsAndTables();

        Scene scene = new Scene(tabPane, 1380, 770);
        stage.setTitle("MCS NUST - Automated Training Program & Conflict-Free Scheduler");
        stage.setScene(scene);
        stage.show();
    }

    // ========================================================================
    // TAB 1: TIMETABLE GRID (WITH EXACT IMAGE COLORS & TUE/WED SEMINARS)
    // ========================================================================
    private VBox buildTimetableTab() {
        Label title = new Label("MCS NUST - Automated Training Program & Timetable System (Fall-2026)");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        Button generateBtn = new Button("⚡ Generate Conflict-Free Timetable");
        generateBtn.setStyle(
                "-fx-background-color: #1b5e20; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");

        Button exportBtn = new Button("📄 Export CSV");
        exportBtn.setStyle(
                "-fx-background-color: #0277bd; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");

        exportBtn.setOnAction(e -> {
            String secName = sectionDropdown.getValue();
            if (secName == null)
                return;
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(secName.replace(" ", "_") + "_Timetable_Fall2026.csv");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
            File file = fc.showSaveDialog(null);
            if (file != null) {
                exportSectionToCSV(sectionMap.get(secName), secName, file);
            }
        });

        sectionDropdown.setPrefWidth(200);

        // Exact Color Legend matching MCS Training Program Image
        HBox legend = new HBox(10,
                makeLegendBadge("Theory", "#ffffff"),
                makeLegendBadge("Lab (3 Hrs)", "#ffee00"),
                makeLegendBadge("STI", "#ffbb00"),
                makeLegendBadge("Seminar/HOD", "#ce93d8"),
                makeLegendBadge("Break / Zohar", "#f5f5f5"),
                makeLegendBadge("Rescheduled", "#f8bbd0"));
        legend.setAlignment(Pos.CENTER_LEFT);

        HBox controls = new HBox(15, new Label("Section:"), sectionDropdown, generateBtn, exportBtn, legend);
        controls.setAlignment(Pos.CENTER_LEFT);

        timetableGrid.setGridLinesVisible(true);
        timetableGrid.setHgap(1);
        timetableGrid.setVgap(1);
        timetableGrid.setStyle("-fx-background-color: #000000; -fx-padding: 4;");

        ScrollPane scrollPane = new ScrollPane(timetableGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(510);

        statusArea.setEditable(false);
        statusArea.setPrefRowCount(3);
        statusArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        generateBtn.setOnAction(e -> {
            TimetableGenerator generator = new TimetableGenerator();
            String result = generator.generateForTerm("Fall-2026");
            statusArea.setText(result);
            if (sectionDropdown.getValue() != null && sectionMap.containsKey(sectionDropdown.getValue())) {
                renderTimetableForSection(sectionMap.get(sectionDropdown.getValue()));
            }
        });

        sectionDropdown.setOnAction(e -> {
            if (sectionDropdown.getValue() != null && sectionMap.containsKey(sectionDropdown.getValue())) {
                renderTimetableForSection(sectionMap.get(sectionDropdown.getValue()));
            }
        });

        VBox box = new VBox(10, title, controls, scrollPane, new Label("Generator Log / Status:"), statusArea);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: #f4f6f9;");
        return box;
    }

    private Label makeLegendBadge(String text, String hex) {
        Label l = new Label(text);
        l.setStyle("-fx-background-color: " + hex
                + "; -fx-border-color: #9e9e9e; -fx-padding: 3 8; -fx-font-size: 11px; -fx-font-weight: bold;");
        return l;
    }

    // ========================================================================
    // TAB 2: MANAGE FACULTY & ROOMS
    // ========================================================================
    private HBox buildMasterDataTab() {
        Label fTitle = new Label("Add Faculty / Lab Engineer");
        fTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        ComboBox<String> rankBox = new ComboBox<>(
                FXCollections.observableArrayList("Dr", "Lt Col", "Maj", "Lec", "Snr Lec", "TVF", "LE"));
        rankBox.setValue("Lec");
        TextField nameField = new TextField();
        nameField.setPromptText("Full Name (e.g., Ayesha Naseer)");
        ComboBox<String> roleBox = new ComboBox<>(
                FXCollections.observableArrayList("INSTRUCTOR", "LAB_ENGINEER", "BOTH"));
        roleBox.setValue("INSTRUCTOR");
        TextField deptField = new TextField("CSE");
        Button addFacultyBtn = new Button("+ Add Faculty");
        addFacultyBtn.setStyle("-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold;");

        addFacultyBtn.setOnAction(e -> {
            if (nameField.getText().isBlank())
                return;
            String sql = "INSERT INTO faculty (rank_title, full_name, designation_role, department) VALUES (?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, rankBox.getValue());
                ps.setString(2, nameField.getText().trim());
                ps.setString(3, roleBox.getValue());
                ps.setString(4, deptField.getText().trim());
                ps.executeUpdate();
                nameField.clear();
                refreshAllDropdownsAndTables();
                showAlert("Success", "Faculty member added to database!");
            } catch (SQLException ex) {
                showAlert("Database Error", ex.getMessage());
            }
        });

        HBox fForm = new HBox(8, rankBox, nameField, roleBox, deptField, addFacultyBtn);
        setupTableColumns(facultyTable, new String[] { "ID", "Rank", "Full Name", "Role", "Dept" });
        VBox leftPane = new VBox(10, fTitle, fForm, facultyTable);
        leftPane.setPadding(new Insets(15));
        HBox.setHgrow(leftPane, Priority.ALWAYS);

        Label rTitle = new Label("Add Classroom / Lab");
        rTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        TextField roomNameField = new TextField();
        roomNameField.setPromptText("Room Name (e.g., CR-37)");
        ComboBox<String> roomTypeBox = new ComboBox<>(
                FXCollections.observableArrayList("CLASSROOM", "LECTURE_HALL", "LAB"));
        roomTypeBox.setValue("CLASSROOM");
        ComboBox<String> specBox = new ComboBox<>(FXCollections.observableArrayList("NONE", "PROGRAMMING", "DATABASE",
                "NETWORKING", "OPERATING_SYSTEM", "SOFTWARE_CONST", "PHYSICS"));
        specBox.setValue("NONE");
        TextField capField = new TextField("55");
        capField.setPrefWidth(60);
        Button addRoomBtn = new Button("+ Add Room");
        addRoomBtn.setStyle("-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold;");

        addRoomBtn.setOnAction(e -> {
            if (roomNameField.getText().isBlank())
                return;
            String sql = "INSERT INTO rooms (room_name, room_type, lab_specialty, capacity) VALUES (?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, roomNameField.getText().trim());
                ps.setString(2, roomTypeBox.getValue());
                if ("NONE".equals(specBox.getValue()))
                    ps.setNull(3, Types.VARCHAR);
                else
                    ps.setString(3, specBox.getValue());
                ps.setInt(4, Integer.parseInt(capField.getText().trim()));
                ps.executeUpdate();
                roomNameField.clear();
                loadRoomTable();
                showAlert("Success", "Room/Lab added to database!");
            } catch (Exception ex) {
                showAlert("Error", ex.getMessage());
            }
        });

        HBox rForm = new HBox(8, roomNameField, roomTypeBox, specBox, capField, addRoomBtn);
        setupTableColumns(roomTable, new String[] { "ID", "Room Name", "Type", "Lab Specialty", "Capacity" });
        VBox rightPane = new VBox(10, rTitle, rForm, roomTable);
        rightPane.setPadding(new Insets(15));
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        return new HBox(10, leftPane, rightPane);
    }

    // ========================================================================
    // TAB 3: COURSE ALLOCATIONS + INSTRUCTOR & LAB ENGINEER REPLACEMENT
    // ========================================================================
    private VBox buildAllocationsTab() {
        Label title = new Label("Course Allocations & Mid-Semester Instructor / Lab Engineer Replacement");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        allocSectionBox.setPromptText("Section");
        allocCourseBox.setPromptText("Course");
        allocInstBox.setPromptText("Instructor");
        allocLEBox.setPromptText("Lab Engineer");

        Spinner<Integer> theorySpin = new Spinner<>(0, 5, 3);
        theorySpin.setPrefWidth(62);
        Spinner<Integer> labSpin = new Spinner<>(0, 2, 0);
        labSpin.setPrefWidth(62);
        Spinner<Integer> stiSpin = new Spinner<>(0, 2, 1);
        stiSpin.setPrefWidth(62);

        Button assignBtn = new Button("+ Allocate");
        assignBtn.setStyle("-fx-background-color: #1b5e20; -fx-text-fill: white; -fx-font-weight: bold;");

        Button deleteAllocBtn = new Button("🗑 Remove");
        deleteAllocBtn.setStyle("-fx-background-color: #b71c1c; -fx-text-fill: white;");

        assignBtn.setOnAction(e -> {
            if (allocSectionBox.getValue() == null || allocCourseBox.getValue() == null
                    || allocInstBox.getValue() == null) {
                showAlert("Validation", "Please select Section, Course, and Instructor.");
                return;
            }
            String sql = "INSERT INTO course_allocations (semester_term, section_id, course_id, instructor_id, lab_engineer_id, theory_slots_per_week, lab_blocks_per_week, sti_slots_per_week) "
                    +
                    "VALUES ('Fall-2026', ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, sectionMap.get(allocSectionBox.getValue()));
                ps.setInt(2, courseMap.get(allocCourseBox.getValue()));
                ps.setInt(3, facultyMap.get(allocInstBox.getValue()));
                if (allocLEBox.getValue() == null || "NONE".equals(allocLEBox.getValue()))
                    ps.setNull(4, Types.INTEGER);
                else
                    ps.setInt(4, leMap.get(allocLEBox.getValue()));
                ps.setInt(5, theorySpin.getValue());
                ps.setInt(6, labSpin.getValue());
                ps.setInt(7, stiSpin.getValue());
                ps.executeUpdate();
                loadAllocationsTable();
                showAlert("Success", "Course allocated!");
            } catch (SQLException ex) {
                showAlert("Database Error", ex.getMessage());
            }
        });

        deleteAllocBtn.setOnAction(e -> {
            ObservableList<String> selected = allocTable.getSelectionModel().getSelectedItem();
            if (selected == null)
                return;
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn
                            .prepareStatement("DELETE FROM course_allocations WHERE allocation_id = ?")) {
                ps.setInt(1, Integer.parseInt(selected.get(0)));
                ps.executeUpdate();
                loadAllocationsTable();
            } catch (SQLException ex) {
                showAlert("Error", ex.getMessage());
            }
        });

        HBox form = new HBox(8, allocSectionBox, allocCourseBox, allocInstBox, allocLEBox,
                new Label("Th:"), theorySpin, new Label("Lab:"), labSpin, new Label("STI:"), stiSpin,
                assignBtn, deleteAllocBtn);
        form.setAlignment(Pos.CENTER_LEFT);

        // Bar 1: Mid-Semester Instructor Replacement
        ComboBox<String> replacementInstBox = new ComboBox<>();
        replacementInstBox.setPromptText("New Instructor");
        replacementInstBox.setPrefWidth(210);
        replacementInstBox.setItems(allocInstBox.getItems());

        Button replaceInstBtn = new Button("🔄 Replace Instructor");
        replaceInstBtn.setStyle("-fx-background-color: #e65100; -fx-text-fill: white; -fx-font-weight: bold;");
        replaceInstBtn.setOnAction(e -> {
            ObservableList<String> selected = allocTable.getSelectionModel().getSelectedItem();
            if (selected == null || replacementInstBox.getValue() == null) {
                showAlert("Selection Required", "Select a row from the table below and choose a New Instructor.");
                return;
            }
            int allocId = Integer.parseInt(selected.get(0));
            int newInstId = facultyMap.get(replacementInstBox.getValue());
            String report = new TimetableGenerator().replaceInstructor("Fall-2026", allocId, newInstId);
            loadAllocationsTable();
            refreshTab1Grid();
            showAlert("Instructor Replacement Report", report);
        });

        // Bar 2: Mid-Semester Lab Engineer (LE) Replacement
        ComboBox<String> replacementLEBox = new ComboBox<>();
        replacementLEBox.setPromptText("New Lab Engineer");
        replacementLEBox.setPrefWidth(210);
        replacementLEBox.setItems(allocLEBox.getItems());

        Button replaceLEBtn = new Button("🧪 Replace Lab Engineer");
        replaceLEBtn.setStyle("-fx-background-color: #00695c; -fx-text-fill: white; -fx-font-weight: bold;");
        replaceLEBtn.setOnAction(e -> {
            ObservableList<String> selected = allocTable.getSelectionModel().getSelectedItem();
            if (selected == null || replacementLEBox.getValue() == null || "NONE".equals(replacementLEBox.getValue())) {
                showAlert("Selection Required",
                        "Select a Lab course row from the table below and choose a New Lab Engineer.");
                return;
            }
            int allocId = Integer.parseInt(selected.get(0));
            int newLeId = leMap.get(replacementLEBox.getValue());
            String report = new TimetableGenerator().replaceLabEngineer("Fall-2026", allocId, newLeId);
            loadAllocationsTable();
            refreshTab1Grid();
            showAlert("Lab Engineer Replacement Report", report);
        });

        HBox replaceBar = new HBox(12,
                new Label("Mid-Semester Replacement (Select Row First):"),
                replacementInstBox, replaceInstBtn,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                replacementLEBox, replaceLEBtn);
        replaceBar.setAlignment(Pos.CENTER_LEFT);
        replaceBar.setStyle(
                "-fx-background-color: #fff3e0; -fx-padding: 10; -fx-border-color: #ffb74d; -fx-border-radius: 5;");

        setupTableColumns(allocTable, new String[] { "ID", "Section", "Course", "Instructor", "Lab Engineer",
                "Theory/Wk", "Lab Blk/Wk", "STI/Wk" });

        VBox box = new VBox(12, title, form, replaceBar, allocTable);
        box.setPadding(new Insets(15));
        return box;
    }

    // ========================================================================
    // TAB 4: TEACHER & ROOM SCHEDULE LOOKUP
    // ========================================================================
    private VBox buildLookupTab() {
        Label title = new Label("Verify Zero-Clash Schedules by Instructor or Room/Lab");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        lookupTypeBox.setItems(FXCollections.observableArrayList("By Faculty / LE", "By Room / Lab"));
        lookupTypeBox.setValue("By Faculty / LE");
        lookupEntityBox.setPrefWidth(260);

        lookupTypeBox.setOnAction(e -> populateLookupEntities());
        lookupEntityBox.setOnAction(e -> runLookupQuery());

        HBox bar = new HBox(12, new Label("Filter Mode:"), lookupTypeBox, new Label("Select:"), lookupEntityBox);
        bar.setAlignment(Pos.CENTER_LEFT);

        setupTableColumns(lookupTable,
                new String[] { "Day", "Period", "Time", "Section", "Course", "Type", "Room / Faculty" });

        VBox box = new VBox(12, title, bar, lookupTable);
        box.setPadding(new Insets(15));
        return box;
    }

    // ========================================================================
    // TAB 5: FREE INSTRUCTORS FINDER (FOR MEETINGS)
    // ========================================================================
    private VBox buildFreeInstructorsTab() {
        Label title = new Label("Find Available / Free Instructors & Lab Engineers for Meetings");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        freeDayBox.setItems(FXCollections.observableArrayList("MON", "TUE", "WED", "THU", "FRI"));
        freeDayBox.setValue("MON");

        freePeriodBox.setItems(FXCollections.observableArrayList(
                "1 (08:00 - 08:50)",
                "2 (09:00 - 09:50)",
                "3 (10:00 - 10:50)",
                "4 (11:00 - 11:50)",
                "5 (12:00 - 12:50)",
                "6 (13:10 - 14:00)",
                "7 (14:20 - 15:10)"));
        freePeriodBox.setValue("1 (08:00 - 08:50)");

        Button searchFreeBtn = new Button("🔍 Show Free Instructors");
        searchFreeBtn.setStyle(
                "-fx-background-color: #1b5e20; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 14;");

        Label countLbl = new Label("");
        countLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #1b5e20;");

        searchFreeBtn.setOnAction(e -> loadFreeInstructors(countLbl));
        freeDayBox.setOnAction(e -> loadFreeInstructors(countLbl));
        freePeriodBox.setOnAction(e -> loadFreeInstructors(countLbl));

        HBox filterBar = new HBox(12, new Label("Select Day:"), freeDayBox, new Label("Select Time Slot:"),
                freePeriodBox, searchFreeBtn, countLbl);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setStyle(
                "-fx-background-color: #e8f5e9; -fx-padding: 10; -fx-border-color: #81c784; -fx-border-radius: 5;");

        setupTableColumns(freeFacultyTable,
                new String[] { "Faculty ID", "Rank", "Full Name", "Role", "Department", "Status" });

        VBox box = new VBox(12, title, filterBar, freeFacultyTable);
        box.setPadding(new Insets(15));
        return box;
    }

    private void loadFreeInstructors(Label countLbl) {
        String day = freeDayBox.getValue();
        String periodStr = freePeriodBox.getValue();
        if (day == null || periodStr == null)
            return;
        int periodNo = Integer.parseInt(periodStr.split(" ")[0]);

        String sql = """
                    SELECT f.faculty_id, f.rank_title, f.full_name, f.designation_role, f.department, 'FREE / AVAILABLE'
                    FROM faculty f
                    WHERE f.faculty_id NOT IN (
                        SELECT sch.instructor_id FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        WHERE sch.semester_term = 'Fall-2026' AND ts.day_of_week = ? AND ts.period_no = ? AND sch.instructor_id IS NOT NULL
                    )
                    AND f.faculty_id NOT IN (
                        SELECT sch.lab_engineer_id FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        WHERE sch.semester_term = 'Fall-2026' AND ts.day_of_week = ? AND ts.period_no = ? AND sch.lab_engineer_id IS NOT NULL
                    )
                    ORDER BY f.department, f.full_name
                """;

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, day);
            ps.setInt(2, periodNo);
            ps.setString(3, day);
            ps.setInt(4, periodNo);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= 6; i++)
                    row.add(rs.getString(i));
                rows.add(row);
            }
            freeFacultyTable.setItems(rows);
            countLbl.setText("Total Free Faculty Members: " + rows.size());
        } catch (SQLException ex) {
            showAlert("Error", ex.getMessage());
        }
    }

    // ========================================================================
    // TAB 6: RESCHEDULE SINGLE CLASS (WITH CONFLICT & EXISTING RESCHEDULE CHECK)
    // ========================================================================
    private VBox buildRescheduleTab() {
        Label title = new Label(
                "Reschedule Individual Class (Auto-Finds Clash-Free Slot & Prevents Double-Teacher Overlap)");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        reschedSectionBox.setPromptText("Select Section (All Semesters)");
        reschedSectionBox.setPrefWidth(200);

        reschedTeacherBox.setPromptText("Select Instructor in Section");
        reschedTeacherBox.setPrefWidth(240);

        reschedSectionBox.setOnAction(e -> loadTeachersForReschedSection());
        reschedTeacherBox.setOnAction(e -> loadScheduledClassesForTeacher());

        Button rescheduleBtn = new Button("🔁 Reschedule Selected Class to Free Slot");
        rescheduleBtn.setStyle(
                "-fx-background-color: #ad1457; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 7 15;");

        rescheduleBtn.setOnAction(e -> {
            ObservableList<String> selected = reschedClassesTable.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showAlert("Select Class", "Please select a scheduled class row from the table to reschedule.");
                return;
            }
            int scheduleId = Integer.parseInt(selected.get(0));
            String report = new TimetableGenerator().rescheduleSingleClass("Fall-2026", scheduleId);
            reschedLogArea.setText(report);
            loadScheduledClassesForTeacher();
            refreshTab1Grid();
        });

        HBox topBar = new HBox(12,
                new Label("1. Section:"), reschedSectionBox,
                new Label("2. Teacher:"), reschedTeacherBox,
                rescheduleBtn);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setStyle(
                "-fx-background-color: #fce4ec; -fx-padding: 10; -fx-border-color: #f48fb1; -fx-border-radius: 5;");

        setupTableColumns(reschedClassesTable, new String[] {
                "Sched ID", "Day", "Period", "Timing", "Course", "Type", "Room", "Rescheduled Status"
        });

        reschedLogArea.setEditable(false);
        reschedLogArea.setPrefRowCount(6);
        reschedLogArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        VBox box = new VBox(12, title, topBar, reschedClassesTable, new Label("Rescheduling & Clash Verification Log:"),
                reschedLogArea);
        box.setPadding(new Insets(15));
        return box;
    }

    private void loadTeachersForReschedSection() {
        String sec = reschedSectionBox.getValue();
        if (sec == null || !sectionMap.containsKey(sec))
            return;
        int sectionId = sectionMap.get(sec);

        reschedTeacherBox.getItems().clear();
        String sql = """
                    SELECT DISTINCT CONCAT(f.rank_title, ' ', f.full_name) AS tname
                    FROM timetable_schedule sch
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    WHERE sch.section_id = ? AND sch.semester_term = 'Fall-2026'
                    ORDER BY tname
                """;
        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next())
                reschedTeacherBox.getItems().add(rs.getString("tname"));
            if (!reschedTeacherBox.getItems().isEmpty())
                reschedTeacherBox.getSelectionModel().selectFirst();
        } catch (SQLException ignored) {
        }
    }

    private void loadScheduledClassesForTeacher() {
        String sec = reschedSectionBox.getValue();
        String teacher = reschedTeacherBox.getValue();
        if (sec == null || teacher == null || !sectionMap.containsKey(sec))
            return;

        String sql = """
                    SELECT sch.schedule_id, ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time),
                           c.course_title, sch.session_type, IFNULL(r.room_name, 'Office/STI'),
                           IF(sch.is_rescheduled = TRUE, '⚠️ ALREADY RESCHEDULED', 'Regular Slot')
                    FROM timetable_schedule sch
                    JOIN time_slots ts ON sch.slot_id = ts.slot_id
                    JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    LEFT JOIN rooms r ON sch.room_id = r.room_id
                    WHERE sch.section_id = ? AND CONCAT(f.rank_title, ' ', f.full_name) = ? AND sch.semester_term = 'Fall-2026'
                    ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                """;
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, sectionMap.get(sec));
            ps.setString(2, teacher);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= 8; i++)
                    row.add(rs.getString(i));
                rows.add(row);
            }
            reschedClassesTable.setItems(rows);
        } catch (SQLException ignored) {
        }
    }

    // ========================================================================
    // RENDER TIMETABLE WITH SEMESTER-SPECIFIC BREAKS & EXACT MCS COLORS
    // ========================================================================
    // ========================================================================
    // RENDER TIMETABLE WITH SEMESTER-SPECIFIC BREAKS & EXACT MCS COLORS
    // ========================================================================
    private void renderTimetableForSection(int sectionId) {
        timetableGrid.getChildren().clear();

        int batchNo = 31;
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn
                        .prepareStatement("SELECT batch_no FROM batch_sections WHERE section_id = ?")) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            if (rs.next())
                batchNo = rs.getInt("batch_no");
        } catch (SQLException ignored) {
        }

        boolean breakAfterP4 = (batchNo == 30 || batchNo == 32);
        String breakTimeLabel = (batchNo == 30 || batchNo == 32) ? "11:50\n-\n12:15"
                : (batchNo == 31 ? "12:45\n-\n13:10" : "12:50\n-\n13:10");

        String[] colHeaders;
        int[] periodToCol = new int[8];
        int breakCol, zoharCol = 8;

        if (breakAfterP4) {
            colHeaders = new String[] {
                    "Day", "1st\n(0800-0850)", "2nd\n(0900-0950)", "3rd\n(1000-1050)", "4th\n(1100-1150)",
                    "BREAK\n" + breakTimeLabel, "5th\n(1215-1305)", "6th\n(1310-1400)",
                    "ZOHAR\n1400-1420", "7th\n(1420-1510)"
            };
            periodToCol[1] = 1;
            periodToCol[2] = 2;
            periodToCol[3] = 3;
            periodToCol[4] = 4;
            breakCol = 5;
            periodToCol[5] = 6;
            periodToCol[6] = 7;
            zoharCol = 8;
            periodToCol[7] = 9;
        } else {
            colHeaders = new String[] {
                    "Day", "1st\n(0800-0850)", "2nd\n(0900-0950)", "3rd\n(1000-1050)", "4th\n(1100-1150)",
                    "5th\n(1200-1250)", "BREAK\n" + breakTimeLabel, "6th\n(1310-1400)",
                    "ZOHAR\n1400-1420", "7th\n(1420-1510)"
            };
            periodToCol[1] = 1;
            periodToCol[2] = 2;
            periodToCol[3] = 3;
            periodToCol[4] = 4;
            periodToCol[5] = 5;
            breakCol = 6;
            periodToCol[6] = 7;
            zoharCol = 8;
            periodToCol[7] = 9;
        }

        for (int c = 0; c < colHeaders.length; c++) {
            Label lbl = new Label(colHeaders[c]);
            lbl.setMaxWidth(Double.MAX_VALUE);
            lbl.setAlignment(Pos.CENTER);
            String bg = (c == breakCol || c == zoharCol) ? "#455a64" : "#1a237e";
            lbl.setStyle("-fx-background-color: " + bg
                    + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6; -fx-font-size: 11px;");
            timetableGrid.add(lbl, c, 0);
        }

        String[] days = { "MON", "TUE", "WED", "THU", "FRI" };
        Map<String, Label> cellMap = new HashMap<>();

        for (int r = 0; r < days.length; r++) {
            String day = days[r];
            Label dayLbl = new Label(day);
            dayLbl.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            dayLbl.setAlignment(Pos.CENTER);
            dayLbl.setStyle(
                    "-fx-background-color: #ffffff; -fx-text-fill: #000000; -fx-font-weight: bold; -fx-padding: 8;");
            timetableGrid.add(dayLbl, 0, r + 1);

            Label breakCell = new Label("FRI".equals(day) ? "-" : "B\nR\nE\nA\nK");
            breakCell.setAlignment(Pos.CENTER);
            breakCell.setPrefSize(52, 84);
            breakCell.setStyle(
                    "-fx-background-color: #ffffff; -fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #000000;");
            timetableGrid.add(breakCell, breakCol, r + 1);

            Label zoharCell = new Label("FRI".equals(day) ? "-" : "Zohar\nPrayer\nBreak");
            zoharCell.setAlignment(Pos.CENTER);
            zoharCell.setPrefSize(58, 84);
            zoharCell.setStyle(
                    "-fx-background-color: #ffffff; -fx-font-weight: bold; -fx-font-size: 10px; -fx-text-fill: #000000;");
            timetableGrid.add(zoharCell, zoharCol, r + 1);

            for (int p = 1; p <= 7; p++) {
                Label cell = new Label("-");
                cell.setWrapText(true);
                cell.setPrefSize(145, 84);
                cell.setAlignment(Pos.CENTER);
                cell.setStyle(
                        "-fx-background-color: #ffffff; -fx-padding: 4; -fx-font-size: 11px; -fx-text-fill: #000000;");

                // Seminar Slots: Tuesday 4th (1100-1150) & Wednesday 3rd + 4th -> Light Purple
                // (#CE93D8)
                if (("TUE".equals(day) && p == 4) || ("WED".equals(day) && (p == 3 || p == 4))) {
                    cell.setText("SEMINAR /\nCI (E) / HOD");
                    cell.setStyle(
                            "-fx-background-color: #ce93d8; -fx-font-weight: bold; -fx-padding: 4; -fx-font-size: 11px; -fx-text-fill: #000000;");
                } else if ("FRI".equals(day) && p == 6) {
                    cell.setText("Friday's\nPrayers");
                    cell.setStyle(
                            "-fx-background-color: #ce93d8; -fx-font-weight: bold; -fx-padding: 4; -fx-font-size: 11px; -fx-text-fill: #000000;");
                } else if ("FRI".equals(day) && p == 7) {
                    cell.setText("Library");
                    cell.setStyle(
                            "-fx-background-color: #e0f7fa; -fx-font-weight: bold; -fx-padding: 4; -fx-font-size: 11px; -fx-text-fill: #000000;");
                }

                timetableGrid.add(cell, periodToCol[p], r + 1);
                cellMap.put(day + "_" + p, cell);
            }
        }

        String query = """
                    SELECT ts.day_of_week, ts.period_no, c.course_title,
                           CONCAT(f.rank_title, ' ', f.full_name) AS inst_name,
                           CONCAT(le.rank_title, ' ', le.full_name) AS le_name,
                           r.room_name, sch.session_type, sch.is_rescheduled
                    FROM timetable_schedule sch
                    JOIN time_slots ts ON sch.slot_id = ts.slot_id
                    JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                    LEFT JOIN rooms r ON sch.room_id = r.room_id
                    WHERE sch.section_id = ? AND sch.semester_term = 'Fall-2026'
                """;

        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Label cell = cellMap.get(rs.getString("day_of_week") + "_" + rs.getInt("period_no"));
                if (cell != null) {
                    String type = rs.getString("session_type");
                    String course = rs.getString("course_title");
                    String inst = rs.getString("inst_name");
                    String room = rs.getString("room_name");
                    boolean isResched = rs.getBoolean("is_rescheduled");
                    String tag = isResched ? " [RESCHEDULED]" : "";

                    if ("STI".equals(type)) {
                        // STI Specific Color: Warm Orange (#FFBB00) matching image
                        cell.setText("STI" + tag + "\n" + inst);
                        cell.setStyle(
                                "-fx-background-color: #ffbb00; -fx-padding: 4; -fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #000000;");
                    } else if ("LAB".equals(type)) {
                        // LAB Specific Color: Bright Yellow (#FFEE00) matching image
                        cell.setText(course + " (LAB)" + tag + "\n" + inst + " / " + rs.getString("le_name") + "\n("
                                + room + ")");
                        cell.setStyle(
                                "-fx-background-color: #ffee00; -fx-font-weight: bold; -fx-padding: 4; -fx-font-size: 10px; -fx-text-fill: #000000;");
                    } else {
                        // Theory Class: Clean White (#FFFFFF)
                        cell.setText(course + tag + "\n" + inst + "\n(" + room + ")");
                        String bg = isResched ? "#f8bbd0" : "#ffffff";
                        cell.setStyle("-fx-background-color: " + bg
                                + "; -fx-padding: 4; -fx-font-size: 11px; -fx-text-fill: #000000;");
                    }
                }
            }
        } catch (SQLException e) {
            statusArea.setText("Error loading grid: " + e.getMessage());
        }
    }

    // ========================================================================
    // DATA LOADERS & HELPERS
    // ========================================================================
    private void refreshAllDropdownsAndTables() {
        loadSections();
        loadCoursesAndFacultyMaps();
        loadFacultyTable();
        loadRoomTable();
        loadAllocationsTable();
        populateLookupEntities();
        loadFreeInstructors(new Label());
    }

    private void refreshTab1Grid() {
        if (sectionDropdown.getValue() != null && sectionMap.containsKey(sectionDropdown.getValue())) {
            renderTimetableForSection(sectionMap.get(sectionDropdown.getValue()));
        }
    }

    private void loadSections() {
        String prev = sectionDropdown.getValue();
        sectionMap.clear();
        sectionDropdown.getItems().clear();
        allocSectionBox.getItems().clear();
        reschedSectionBox.getItems().clear();

        String sql = "SELECT bs.section_id, CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS label " +
                "FROM batch_sections bs JOIN programs p ON bs.program_id = p.program_id ORDER BY bs.batch_no, bs.section_name";
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String label = rs.getString("label");
                sectionMap.put(label, rs.getInt("section_id"));
                sectionDropdown.getItems().add(label);
                allocSectionBox.getItems().add(label);
                reschedSectionBox.getItems().add(label);
            }
            if (prev != null && sectionMap.containsKey(prev))
                sectionDropdown.setValue(prev);
            else if (!sectionDropdown.getItems().isEmpty())
                sectionDropdown.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            statusArea.setText("DB Error: " + e.getMessage());
        }
    }

    private void loadCoursesAndFacultyMaps() {
        courseMap.clear();
        facultyMap.clear();
        leMap.clear();
        allocCourseBox.getItems().clear();
        allocInstBox.getItems().clear();
        allocLEBox.getItems().clear();
        allocLEBox.getItems().add("NONE");

        try (Connection conn = DatabaseConnection.getConnection(); Statement st = conn.createStatement()) {
            ResultSet rsC = st.executeQuery(
                    "SELECT course_id, CONCAT(course_code, ' - ', course_title) AS lbl FROM courses ORDER BY course_code");
            while (rsC.next()) {
                courseMap.put(rsC.getString("lbl"), rsC.getInt("course_id"));
                allocCourseBox.getItems().add(rsC.getString("lbl"));
            }
            ResultSet rsF = st.executeQuery(
                    "SELECT faculty_id, CONCAT(rank_title, ' ', full_name) AS lbl, designation_role FROM faculty ORDER BY full_name");
            while (rsF.next()) {
                String lbl = rsF.getString("lbl");
                int id = rsF.getInt("faculty_id");
                String role = rsF.getString("designation_role");
                if (!"LAB_ENGINEER".equals(role)) {
                    facultyMap.put(lbl, id);
                    allocInstBox.getItems().add(lbl);
                }
                if (!"INSTRUCTOR".equals(role)) {
                    leMap.put(lbl, id);
                    allocLEBox.getItems().add(lbl);
                }
            }
        } catch (SQLException ignored) {
        }
    }

    private void loadFacultyTable() {
        fillTable(facultyTable,
                "SELECT faculty_id, rank_title, full_name, designation_role, department FROM faculty ORDER BY faculty_id DESC");
    }

    private void loadRoomTable() {
        fillTable(roomTable,
                "SELECT room_id, room_name, room_type, IFNULL(lab_specialty, '-'), capacity FROM rooms ORDER BY room_type, room_name");
    }

    private void loadAllocationsTable() {
        String sql = """
                    SELECT ca.allocation_id,
                           CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                           c.course_title,
                           CONCAT(f.rank_title, ' ', f.full_name),
                           IFNULL(CONCAT(le.rank_title, ' ', le.full_name), '-'),
                           ca.theory_slots_per_week, ca.lab_blocks_per_week, ca.sti_slots_per_week
                    FROM course_allocations ca
                    JOIN batch_sections bs ON ca.section_id = bs.section_id
                    JOIN programs p ON bs.program_id = p.program_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON ca.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON ca.lab_engineer_id = le.faculty_id
                    ORDER BY bs.batch_no, bs.section_name, c.course_code
                """;
        fillTable(allocTable, sql);
    }

    private void populateLookupEntities() {
        lookupEntityBox.getItems().clear();
        String mode = lookupTypeBox.getValue();
        String sql = "By Faculty / LE".equals(mode)
                ? "SELECT CONCAT(rank_title, ' ', full_name) AS name FROM faculty ORDER BY full_name"
                : "SELECT room_name AS name FROM rooms ORDER BY room_name";
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                lookupEntityBox.getItems().add(rs.getString("name"));
            if (!lookupEntityBox.getItems().isEmpty())
                lookupEntityBox.getSelectionModel().selectFirst();
        } catch (SQLException ignored) {
        }
    }

    private void runLookupQuery() {
        String selected = lookupEntityBox.getValue();
        if (selected == null)
            return;
        String mode = lookupTypeBox.getValue();

        String sql = "By Faculty / LE".equals(mode)
                ? """
                        SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time),
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                               c.course_title, sch.session_type, IFNULL(r.room_name, 'Office/STI')
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON sch.instructor_id = f.faculty_id
                        LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                        LEFT JOIN rooms r ON sch.room_id = r.room_id
                        WHERE CONCAT(f.rank_title, ' ', f.full_name) = ? OR CONCAT(le.rank_title, ' ', le.full_name) = ?
                        ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                        """
                : """
                        SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time),
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                               c.course_title, sch.session_type, CONCAT(f.rank_title, ' ', f.full_name)
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON sch.instructor_id = f.faculty_id
                        JOIN rooms r ON sch.room_id = r.room_id
                        WHERE r.room_name = ?
                        ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                        """;

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, selected);
            if ("By Faculty / LE".equals(mode))
                ps.setString(2, selected);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= 7; i++)
                    row.add(rs.getString(i));
                rows.add(row);
            }
            lookupTable.setItems(rows);
        } catch (SQLException ignored) {
        }
    }

    private void setupTableColumns(TableView<ObservableList<String>> table, String[] cols) {
        table.getColumns().clear();
        for (int i = 0; i < cols.length; i++) {
            final int colIdx = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(cols[i]);
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(colIdx)));
            col.setPrefWidth(145);
            table.getColumns().add(col);
        }
    }

    private void fillTable(TableView<ObservableList<String>> table, String sql) {
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            int colCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= colCount; i++)
                    row.add(rs.getString(i));
                data.add(row);
            }
            table.setItems(data);
        } catch (SQLException ignored) {
        }
    }

    private void showAlert(String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void exportSectionToCSV(int sectionId, String sectionName, File file) {
        String sql = """
                    SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time) AS timing,
                           c.course_code, c.course_title, sch.session_type,
                           CONCAT(f.rank_title, ' ', f.full_name) AS instructor,
                           IFNULL(CONCAT(le.rank_title, ' ', le.full_name), '-') AS lab_engineer,
                           IFNULL(r.room_name, 'N/A') AS room,
                           IF(sch.is_rescheduled = TRUE, 'YES', 'NO') AS rescheduled
                    FROM timetable_schedule sch
                    JOIN time_slots ts ON sch.slot_id = ts.slot_id
                    JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                    LEFT JOIN rooms r ON sch.room_id = r.room_id
                    WHERE sch.section_id = ? AND sch.semester_term = 'Fall-2026'
                    ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                """;
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                PrintWriter pw = new PrintWriter(file)) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            pw.println(
                    "Section,Day,Period,Timing,Course Code,Course Title,Session Type,Instructor,Lab Engineer,Room,Rescheduled");
            while (rs.next()) {
                pw.printf("\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        sectionName, rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10));
            }
            showAlert("Export Complete", "Timetable exported successfully to:\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            showAlert("Export Error", ex.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}