# Automated Academic Training Program & Conflict-Free Timetable Scheduling System

A desktop-based relational database and automated timetable scheduling system built for the **Department of Computer Software Engineering, Military College of Signals (MCS), NUST**. This application replaces manual spreadsheet-based scheduling with a normalized **3NF MySQL database** and a **JavaFX** desktop interface that dynamically generates clash-free weekly training programs for Undergraduate (`BESE`) and Postgraduate (`MSSE`/`MSCS`) batches.

---

## 📋 Table of Contents
- [Problem Statement](#-problem-statement)
- [Key Features](#-key-features)
- [Tech Stack & Versions](#-tech-stack--versions)
- [Database Architecture](#-database-architecture)
- [Project Structure](#-project-structure)
- [Setup & Installation (VS Code)](#-setup--installation-vs-code)
- [Team Members](#-team-members)

---

## 🎯 Problem Statement
Compiling weekly semester training programs manually across multiple batches (`BESE-29` to `BESE-32`), sections, instructors, lab engineers, classrooms, and specialized laboratories is error-prone and time-consuming. Manual scheduling often leads to room double-bookings, overlapping instructor or lab engineer timings, split lab sessions, and conflicts with fixed institutional events such as Wednesday seminars, Friday prayer breaks, and Saturday maintenance days.

---

## ✨ Key Features
* **Zero-Conflict Database Enforcement:** Uses composite `UNIQUE` constraints (`uq_no_section_clash`, `uq_no_room_clash`, `uq_no_instructor_clash`, `uq_no_le_clash`) and a `BEFORE INSERT` MySQL trigger (`trg_prevent_blocked_slot_insert`) to guarantee 100% clash prevention at the database engine level.
* **Automated Constraint-Satisfaction Scheduling:** Dynamically allocates weekly theory classes, **3-slot consecutive lab blocks** (`lab_group_tag`), and Student-Teacher Interaction (`STI`) sessions based on course credit hours and required lab specialties.
* **MCS NUST Institutional Rule Compliance:** Automatically handles distinct Monday–Thursday vs. Friday schedules, morning (`UG`) vs. evening (`PG`) shifts, Wednesday `10:00–11:50` Seminar/CI/HOD blocks, Friday `12:35–14:15` prayer breaks, and Saturday maintenance days.
* **Master Data & Course Allocation Management:** Centralized CRUD operations for degree programs, batches, sections, faculty, lab engineers, classrooms, specialized labs, and semester course loads.
* **Multi-Perspective Timetable Views & Export:** View schedules by **Section**, **Faculty**, or **Room**, validate manual overrides in real time, and export schedules matching the official MCS training program format.

---

## 🛠️ Tech Stack & Versions
Configured via Apache Maven (`pom.xml`):
* **IDE:** Visual Studio Code (with Extension Pack for Java)
* **Language:** Java 11 (`JDK 11`)
* **Frontend / UI:** JavaFX 13 (`javafx-controls`, `javafx-fxml`)
* **Database:** MySQL (3NF Relational Schema)
* **Database Connectivity:** MySQL Connector/J `9.0.0` (JDBC)
* **Build Tool:** Apache Maven (`javafx-maven-plugin 0.0.6`)

---

## 🗄️ Database Architecture
The database (`mcs_timetable_db`) is normalized to **Third Normal Form (3NF)** and modeled using Crow's Foot notation across **8 core tables**:

| Table Name | Description |
| :--- | :--- |
| `programs` | Degree programs (`BESE`, `MSSE`, `MSCS`) and shift types (`MORNING` / `EVENING`). |
| `faculty` | Instructors and Lab Engineers with ranks, roles, departments, and weekly slot limits. |
| `batch_sections` | Batch numbers, section names, current semester, student strength, and class advisors. |
| `rooms` | Classrooms, lecture halls, and specialized labs (`DATABASE`, `NETWORKING`, `PROGRAMMING`, etc.). |
| `courses` | Course catalog detailing theory credit hours, lab credit hours, and required lab specialties. |
| `time_slots` | Master slot definitions across `MON_THU`, `FRIDAY`, `SATURDAY`, and `EVENING_MON_FRI` with global block flags. |
| `course_allocations` | Term-specific mapping of courses, instructors, and lab engineers to batch sections. |
| `timetable_schedule` | Generated conflict-free schedule storing slot, room, section, instructor, and lab engineer assignments. |

---

## 📁 Project Structure
```text
DB_Project_Code/
├── .github/                              # GitHub workflows and repository templates
├── .vscode/                              # VS Code workspace settings and launch configurations
└── timetable/                            # Main Maven & JavaFX project root
    ├── src/main/
    │   ├── java/
    │   │   ├── com/mcs/timetable/
    │   │   │   ├── controller/
    │   │   │   │   └── DashboardController.java      # Main JavaFX UI controller
    │   │   │   ├── db/
    │   │   │   │   └── DatabaseConnection.java       # JDBC MySQL connection handler
    │   │   │   ├── engine/
    │   │   │   │   └── TimetableGenerator.java       # Automated conflict-free scheduling engine
    │   │   │   ├── model/
    │   │   │   │   ├── AllocationTask.java           # Course & load allocation model
    │   │   │   │   ├── ScheduleCell.java             # Timetable grid cell entity
    │   │   │   │   └── TimeSlot.java                 # Master time slot model
    │   │   │   └── MainApp.java                      # JavaFX application entry point
    │   │   └── module-info.java                      # Java module system declarations
    │   └── resources/                                # FXML views, stylesheets, and assets
    ├── target/                                       # Compiled Maven build output (ignored in Git)
    └── pom.xml                                       # Maven dependencies & JavaFX plugin config
```

---

## 🚀 Setup & Installation (VS Code)

### 1. Prerequisites
* **JDK 11** installed and configured in your system `PATH`.
* **MySQL Server 8.0+** running locally.
* **Apache Maven** installed (or using the Maven wrapper/extension in VS Code).
* **Visual Studio Code** with the **Extension Pack for Java** (`vscjava.vscode-java-pack`) installed.

### 2. Database Setup
1. Open MySQL Workbench or your terminal's MySQL client.
2. Execute the schema script to create the `mcs_timetable_db` database, tables, `UNIQUE` constraints, and trigger:
   ```sql
   SOURCE database/schema.sql;
   ```
3. Load the Fall-2026 seed data:
   ```sql
   SOURCE database/seed_fall_2026.sql;
   ```
4. Open `timetable/src/main/java/com/mcs/timetable/db/DatabaseConnection.java` in VS Code and update your local MySQL credentials (`URL`, `USER`, `PASSWORD`).

### 3. Run the Application in VS Code
1. Clone the repository and open the `DB_Project_Code` folder in **VS Code**:
   ```bash
   git clone https://github.com/J-hassan/Database-Project.git
   code DB_Project_Code
   ```
2. Open the integrated terminal in VS Code (`Ctrl + ~`) and navigate to the `timetable` module directory:
   ```bash
   cd timetable
   ```
3. Run the JavaFX application using Maven:
   ```bash
   mvn clean javafx:run
   ```

---

## 👥 Team Members
**Department of Computer Software Engineering — Military College of Signals (MCS), NUST**

| Name | Role |
| :--- | :--- | :--- |
| **Muhammad Hassan** | Core Scheduling Engine, Algorithm & JDBC Integration |
| **Abdul Rahman** | JavaFX UI Design & Master Data Management (CRUD) |
| **Hussnain Farooq** | Interactive Timetable Views, Clash Validation & Export Reporting |
