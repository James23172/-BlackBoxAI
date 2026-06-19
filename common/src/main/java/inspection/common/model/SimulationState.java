package inspection.common.model;

import java.util.List;

/**
 * 完整仿真状态快照
 * Display 模块将其序列化为 JSON 推送给前端
 */
public class SimulationState {
    public boolean[][] mapView;               // 地图探索状态
    public int mapWidth;
    public int mapHeight;
    public List<Point> obstacles;              // 障碍物坐标列表
    public List<CarState> cars;                // 所有小车状态
    public double exploredRate;                // 探索率 0.0 ~ 1.0
    public boolean taskActive;                 // 任务是否激活
    public long tick;                          // 当前节拍号
    public boolean completed;                  // 是否巡检完成

    public SimulationState() {}

    // ===== getters =====
    public boolean[][] getMapView() { return mapView; }
    public void setMapView(boolean[][] mapView) { this.mapView = mapView; }

    public int getMapWidth() { return mapWidth; }
    public void setMapWidth(int mapWidth) { this.mapWidth = mapWidth; }

    public int getMapHeight() { return mapHeight; }
    public void setMapHeight(int mapHeight) { this.mapHeight = mapHeight; }

    public List<Point> getObstacles() { return obstacles; }
    public void setObstacles(List<Point> obstacles) { this.obstacles = obstacles; }

    public List<CarState> getCars() { return cars; }
    public void setCars(List<CarState> cars) { this.cars = cars; }

    public double getExploredRate() { return exploredRate; }
    public void setExploredRate(double exploredRate) { this.exploredRate = exploredRate; }

    public boolean isTaskActive() { return taskActive; }
    public void setTaskActive(boolean taskActive) { this.taskActive = taskActive; }

    public long getTick() { return tick; }
    public void setTick(long tick) { this.tick = tick; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
}
