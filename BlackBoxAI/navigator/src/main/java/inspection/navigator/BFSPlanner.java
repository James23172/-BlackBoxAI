package inspection.navigator;

import inspection.common.model.Point;
import java.util.*;

/**
 * BFS 路径规划器
 * 保证在无权图中找到最短路径（按步数）
 */
public class BFSPlanner implements PathPlanner {

    private static final int[][] DIRECTIONS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}}; // 上下左右

    @Override
    public List<Point> plan(Point start, Point target, boolean[][] obstacles, int width, int height) {
        if (start.equals(target)) {
            List<Point> path = new ArrayList<>();
            path.add(start);
            return path;
        }

        Queue<Point> queue = new LinkedList<>();
        boolean[][] visited = new boolean[height][width];
        Map<Point, Point> parent = new HashMap<>();

        queue.add(start);
        visited[start.getY()][start.getX()] = true;

        while (!queue.isEmpty()) {
            Point current = queue.poll();
            if (current.equals(target)) {
                return reconstructPath(parent, target);
            }

            for (int[] dir : DIRECTIONS) {
                int nx = current.getX() + dir[0];
                int ny = current.getY() + dir[1];
                if (nx >= 0 && nx < width && ny >= 0 && ny < height
                        && !visited[ny][nx]
                        && !obstacles[ny][nx]) {
                    visited[ny][nx] = true;
                    Point neighbor = new Point(nx, ny);
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        return null; // 无路径
    }

    /**
     * 从 parent 映射中回溯路径
     */
    private List<Point> reconstructPath(Map<Point, Point> parent, Point target) {
        LinkedList<Point> path = new LinkedList<>();
        Point current = target;
        while (current != null) {
            path.addFirst(current);
            current = parent.get(current);
        }
        return path;
    }
}
