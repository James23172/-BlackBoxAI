package inspection.car;

import inspection.common.client.BlackboardClient;
import inspection.common.model.Point;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DynamicObstacleManager {
    private static final Logger log = LoggerFactory.getLogger(DynamicObstacleManager.class);

    private final BlackboardClient bb;

    public DynamicObstacleManager(BlackboardClient bb) {
        this.bb = bb;
    }

    public void clearObstacle(Point pos) {
        if (pos != null) {
            bb.clearBlocked(pos.x, pos.y);
            log.debug("清除动态障碍 ({},{})", pos.x, pos.y);
        }
    }

    public void setObstacle(Point pos) {
        if (pos != null) {
            bb.setBlocked(pos.x, pos.y);
            log.debug("设置动态障碍 ({},{})", pos.x, pos.y);
        }
    }
}
