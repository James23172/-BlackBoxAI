package inspection.navigator;

import inspection.common.model.Point;
import java.util.List;

public interface PathPlanner {
    List<Point> plan(Point start, Point target, boolean[][] obstacles, int width, int height);
}