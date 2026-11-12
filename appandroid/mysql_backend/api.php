<?php
/**
 * EcoTech WaterGuard - API REST para Servidor MySQL
 * Recibe peticiones JSON desde la App Android para sincronizar estado de garrafones, alertas y recargas.
 */

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');

$host = 'localhost';
$db_user = 'root';
$db_pass = ''; // Modificar según contraseña MySQL
$db_name = 'ecotech_water_db';

try {
    $pdo = new PDO("mysql:host=$host;dbname=$db_name;charset=utf8mb4", $db_user, $db_pass, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC
    ]);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(["status" => "error", "message" => "Error de conexión MySQL: " . $e->getMessage()]);
    exit;
}

$input = json_decode(file_get_contents('php://input'), true);

if (!$input) {
    echo json_encode(["status" => "online", "message" => "EcoTech WaterGuard API activa"]);
    exit;
}

$action = $input['action'] ?? '';

switch ($action) {
    case 'sync_dispenser':
        $stmt = $pdo->prepare("INSERT INTO dispensadores (id, name, location, current_level_percent, status, sensor_id)
            VALUES (:id, :name, :location, :level, :status, :sensor)
            ON DUPLICATE KEY UPDATE
                current_level_percent = :level,
                status = :status,
                last_refill_timestamp = CURRENT_TIMESTAMP");
        $stmt->execute([
            ':id' => $input['dispenser_id'],
            ':name' => $input['name'],
            ':location' => $input['location'],
            ':level' => $input['level_percent'],
            ':status' => $input['status'],
            ':sensor' => $input['sensor_id']
        ]);
        echo json_encode(["status" => "success", "message" => "Dispensador sincronizado"]);
        break;

    case 'create_alert':
        $stmt = $pdo->prepare("INSERT INTO alertas (id, dispenser_id, dispenser_name, location, level_percent, severity, timestamp)
            VALUES (:id, :dispenser_id, :dispenser_name, :location, :level, :severity, :timestamp)
            ON DUPLICATE KEY UPDATE level_percent = :level, severity = :severity");
        $stmt->execute([
            ':id' => $input['alert_id'],
            ':dispenser_id' => $input['dispenser_id'],
            ':dispenser_name' => $input['dispenser_name'],
            ':location' => $input['location'],
            ':level' => $input['level_percent'],
            ':severity' => $input['severity'],
            ':timestamp' => $input['timestamp']
        ]);
        echo json_encode(["status" => "success", "message" => "Alerta registrada en MySQL"]);
        break;

    case 'log_refill':
        $stmt = $pdo->prepare("INSERT INTO historial_recargas (id, dispenser_id, dispenser_name, location, technician_name, notes, refill_timestamp)
            VALUES (:id, :dispenser_id, :dispenser_name, :location, :tech, :notes, :timestamp)");
        $stmt->execute([
            ':id' => $input['log_id'],
            ':dispenser_id' => $input['dispenser_id'],
            ':dispenser_name' => $input['dispenser_name'],
            ':location' => $input['location'],
            ':tech' => $input['technician_name'],
            ':notes' => $input['notes'],
            ':timestamp' => $input['timestamp']
        ]);

        // Actualizar el estado del dispensador en la base de datos MySQL
        $updateStmt = $pdo->prepare("UPDATE dispensadores SET current_level_percent = 100, status = 'OK' WHERE id = :id");
        $updateStmt->execute([':id' => $input['dispenser_id']]);

        echo json_encode(["status" => "success", "message" => "Recarga de garrafón registrada en MySQL"]);
        break;

    case 'get_dispensers':
        $stmt = $pdo->query("SELECT * FROM dispensadores ORDER BY id ASC");
        echo json_encode(["status" => "success", "data" => $stmt->fetchAll()]);
        break;

    default:
        echo json_encode(["status" => "error", "message" => "Acción desconocida"]);
        break;
}
