package inspection.common.model;

import inspection.common.enums.CarStatus;

/**
 * 单台小车状态快照
 * 供 Controller 和 Display 使用
 */
public class CarState {
    public String carId;
    public CarStatus status;
    public Point position;
    public Point target;
    public int steps;
    public long blockedTick;
    public String owner;                        // 归属 machineId (主/B/C/D/E)

    public CarState() {}

    public CarState(String carId) {
        this.carId = carId;
    }

    public String getCarId() { return carId; }
    public void setCarId(String carId) { this.carId = carId; }

    public CarStatus getStatus() { return status; }
    public void setStatus(CarStatus status) { this.status = status; }

    public Point getPosition() { return position; }
    public void setPosition(Point position) { this.position = position; }

    public Point getTarget() { return target; }
    public void setTarget(Point target) { this.target = target; }

    public int getSteps() { return steps; }
    public void setSteps(int steps) { this.steps = steps; }

    public long getBlockedTick() { return blockedTick; }
    public void setBlockedTick(long blockedTick) { this.blockedTick = blockedTick; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    @Override
    public String toString() {
        return "CarState{carId='" + carId + "', status=" + status +
                ", pos=" + position + ", target=" + target +
                ", steps=" + steps + "}";
    }
}
