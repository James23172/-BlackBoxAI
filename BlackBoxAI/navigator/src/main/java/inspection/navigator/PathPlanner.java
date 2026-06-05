package inspection.navigator;

import inspection.common.model.Point;
import java.util.List;

/**
 * 路径规划器接口
 */
public interface PathPlanner {
    /**
     * 规划从起点到终点的路径
     * @param start 起点坐标
     * @param target 目标点坐标
     * @param obstacles 障碍物矩阵，true 表示不可通行
     * @param width 地图宽度
     * @param height 地图高度
     * @return 从起点到终点的路径点列表（包含起点和终点），若无法到达返回 null 或空列表
     */
    List<Point> plan(Point start, Point target, boolean[][] obstacles, int width, int height);
}
