-- ==========================================================
-- ECOTECH WATERGUARD - SISTEMA DE MONITOREO DE DISPENSADORES DE AGUA
-- ESQUEMA DE BASE DE DATOS MYSQL
-- ==========================================================

CREATE DATABASE IF NOT EXISTS `ecotech_water_db` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `ecotech_water_db`;

-- Tabla 1: Dispensadores de Agua y Sensores ESP32
CREATE TABLE IF NOT EXISTS `dispensadores` (
    `id` VARCHAR(50) NOT NULL PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL,
    `location` VARCHAR(150) NOT NULL,
    `current_level_percent` INT NOT NULL DEFAULT 100,
    `capacity_liters` DECIMAL(5,2) NOT NULL DEFAULT 20.00,
    `status` ENUM('OK', 'LOW_WATER', 'EMPTY', 'MAINTENANCE') NOT NULL DEFAULT 'OK',
    `sensor_id` VARCHAR(50) NOT NULL,
    `last_refill_timestamp` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tabla 2: Alertas de Garrafones Bajos / Vacíos
CREATE TABLE IF NOT EXISTS `alertas` (
    `id` VARCHAR(50) NOT NULL PRIMARY KEY,
    `dispenser_id` VARCHAR(50) NOT NULL,
    `dispenser_name` VARCHAR(100) NOT NULL,
    `location` VARCHAR(150) NOT NULL,
    `level_percent` INT NOT NULL,
    `severity` ENUM('WARNING', 'CRITICAL') NOT NULL,
    `timestamp` BIGINT NOT NULL,
    `is_resolved` TINYINT(1) DEFAULT 0,
    `resolved_by` VARCHAR(100) NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`dispenser_id`) REFERENCES `dispensadores`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tabla 3: Historial de Reemplazo / Recarga de Vidones de Agua
CREATE TABLE IF NOT EXISTS `historial_recargas` (
    `id` VARCHAR(50) NOT NULL PRIMARY KEY,
    `dispenser_id` VARCHAR(50) NOT NULL,
    `dispenser_name` VARCHAR(100) NOT NULL,
    `location` VARCHAR(150) NOT NULL,
    `technician_name` VARCHAR(100) NOT NULL,
    `notes` TEXT NULL,
    `refill_timestamp` BIGINT NOT NULL,
    `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`dispenser_id`) REFERENCES `dispensadores`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Datos Iniciales de Ejemplo
INSERT INTO `dispensadores` (`id`, `name`, `location`, `current_level_percent`, `capacity_liters`, `status`, `sensor_id`) VALUES
('DISP-001', 'Dispensador Principal', 'Piso 1 - Recepción Central', 85, 20.00, 'OK', 'ESP32-W001'),
('DISP-002', 'Dispensador Cafetería', 'Piso 2 - Sala de Descanso', 25, 20.00, 'LOW_WATER', 'ESP32-W002'),
('DISP-003', 'Dispensador Operaciones', 'Planta Baja - Área Industrial', 8, 20.00, 'EMPTY', 'ESP32-W003'),
('DISP-004', 'Dispensador Gerencia', 'Piso 3 - Salas VIP', 95, 20.00, 'OK', 'ESP32-W004')
ON DUPLICATE KEY UPDATE `current_level_percent` = VALUES(`current_level_percent`), `status` = VALUES(`status`);
