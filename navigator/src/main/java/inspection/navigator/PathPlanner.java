package inspection.navigator;

import inspection.common.model.Point;
import java.util.List;

public interface PathPlanner {
    /**
     * 规划从起点到终点的路径
     *
     * @param start     起点
     * @param target    终点
     * @param obstacles 障碍物网格 (true=障碍物)
     * @param explored  已探索网格 (true=已探索)，用于加权BFS优先走未探索区域
     * @param width     地图宽度
     * @param height    地图高度
     * @return 路径点列表（含起点），找不到路径返回 null
     */
    List<Point> plan(Point start, Point target, boolean[][] obstacles, boolean[][] explored, int width, int height);
}