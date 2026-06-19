package inspection.navigator;

import inspection.common.model.Point;
import java.util.*;

/**
 * 加权 BFS 路径规划器 (0-1 BFS)
 * 使用双端队列实现：未探索邻居优先处理（插入队首，cost=0），
 * 已探索邻居延后处理（插入队尾，cost=1）。
 * 在保证找到可达路径的同时，优先经过未探索区域，提升探索效率。
 */
public class BFSPlanner implements PathPlanner {

    private static final int[][] DIRECTIONS = {{0, 1}, {0, -1}, {1, 0}, {-1, 0}};

    @Override
    public List<Point> plan(Point start, Point target, boolean[][] obstacles,
                            boolean[][] explored, int width, int height) {
        if (start.equals(target)) {
            List<Point> path = new ArrayList<>();
            path.add(start);
            return path;
        }

        // 0-1 BFS: 使用双端队列
        // 未探索格子 → offerFirst (cost=0，优先处理)
        // 已探索格子 → offerLast  (cost=1，延后处理)
        Deque<Point> deque = new ArrayDeque<>();
        boolean[][] visited = new boolean[height][width];
        Map<Point, Point> parent = new HashMap<>();

        deque.addLast(start);
        visited[start.getY()][start.getX()] = true;

        while (!deque.isEmpty()) {
            Point current = deque.pollFirst();
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

                    // 0-1 BFS 关键逻辑:
                    // 未探索格子 → 优先走 (插入队首)
                    // 已探索格子 → 可走但延后 (插入队尾)
                    if (explored != null && explored[ny][nx]) {
                        deque.addLast(neighbor);
                    } else {
                        deque.addFirst(neighbor);
                    }
                }
            }
        }
        return null;
    }

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