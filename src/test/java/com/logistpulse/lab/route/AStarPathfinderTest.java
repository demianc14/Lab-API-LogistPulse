package com.logistpulse.lab.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Optional;
import java.util.PriorityQueue;
import org.junit.jupiter.api.Test;

class AStarPathfinderTest {

    private final WarehouseGrid grid = WarehouseGrid.defaultLayout();
    private final AStarPathfinder finder = new AStarPathfinder(grid);

    @Test
    void straightCorridorCostsOnePerStep() {
        RouteDto r = finder.find(new Point(0, 0), new Point(11, 0)).orElseThrow();
        assertThat(r.steps()).isEqualTo(11);
        assertThat(r.cost()).isEqualTo(11);
    }

    @Test
    void blockedDestinationHasNoRoute() {
        assertThat(finder.find(new Point(0, 0), new Point(1, 1))).isEmpty();
    }

    @Test
    void outOfBoundsHasNoRoute() {
        assertThat(finder.find(new Point(0, 0), new Point(99, 99))).isEmpty();
    }

    @Test
    void pathNeverCrossesShelves() {
        RouteDto r = finder.find(new Point(0, 0), new Point(11, 7)).orElseThrow();
        assertThat(r.path()).allMatch(grid::walkable);
        assertThat(r.path().get(r.path().size() - 1)).isEqualTo(new Point(11, 7));
    }

    @Test
    void avoidsCongestedZoneWhenDetourIsCheaper() {
        // Directo por la zona congestionada costaría 3×2 + 1 = 7; rodeando por la fila 2 cuesta 6.
        Optional<RouteDto> r = finder.find(new Point(3, 3), new Point(7, 3));
        assertThat(r).isPresent();
        assertThat(r.get().cost()).isEqualTo(6);
        assertThat(r.get().path()).noneMatch(p -> grid.rows().get(p.y()).charAt(p.x()) == ',');
    }

    @Test
    void consecutiveCellsAreAdjacent() {
        RouteDto r = finder.find(new Point(0, 0), new Point(10, 5)).orElseThrow();
        Point prev = new Point(0, 0);
        for (Point p : r.path()) {
            assertThat(Math.abs(p.x() - prev.x()) + Math.abs(p.y() - prev.y())).isEqualTo(1);
            prev = p;
        }
    }

    // --- Parte 5.3: congestionada pasa de costo 5 a 2 ---

    @Test
    void crossesTheCongestedZoneWhenItIsNowCheaper() {
        // Con costo 5 el óptimo de (3,0)→(5,5) era 11 (rodeando); con costo 2 cruzar la zona da 9.
        RouteDto r = finder.find(new Point(3, 0), new Point(5, 5)).orElseThrow();
        assertThat(r.cost()).isEqualTo(9);
        assertThat(r.path()).anyMatch(p -> grid.rows().get(p.y()).charAt(p.x()) == ',');
    }

    @Test
    void destinationInsideTheZoneIsCheaperNow() {
        // Era 15 con costo 5; con costo 2 conviene cruzar más celdas de la zona: 11 (comprobado con un Dijkstra aparte).
        RouteDto r = finder.find(new Point(0, 0), new Point(5, 4)).orElseThrow();
        assertThat(r.cost()).isEqualTo(11);
    }

    @Test
    void manhattanHeuristicStaysAdmissibleSoAStarIsOptimalOnEveryPair() {
        // Costo mínimo por celda = 1, así que Manhattan nunca sobreestima. Compruebo contra Dijkstra (sin heurística).
        var cells = new ArrayList<Point>();
        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                if (grid.walkable(x, y)) {
                    cells.add(new Point(x, y));
                }
            }
        }
        for (Point from : cells) {
            int[] best = dijkstra(from);
            for (Point to : cells) {
                int expected = best[to.y() * grid.width() + to.x()];
                assertThat(finder.find(from, to).orElseThrow().cost())
                        .as("%s -> %s", from, to).isEqualTo(expected);
            }
        }
    }

    /** Distancias mínimas desde "from" a todas las celdas, sin heurística. */
    private int[] dijkstra(Point from) {
        int w = grid.width();
        int[] dist = new int[w * grid.height()];
        java.util.Arrays.fill(dist, Integer.MAX_VALUE);
        dist[from.y() * w + from.x()] = 0;
        var pq = new PriorityQueue<int[]>((a, b) -> Integer.compare(a[1], b[1]));
        pq.add(new int[] {from.y() * w + from.x(), 0});
        while (!pq.isEmpty()) {
            int[] cur = pq.poll();
            if (cur[1] > dist[cur[0]]) {
                continue;
            }
            int cx = cur[0] % w;
            int cy = cur[0] / w;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = cx + d[0];
                int ny = cy + d[1];
                if (grid.walkable(nx, ny) && cur[1] + grid.cost(nx, ny) < dist[ny * w + nx]) {
                    dist[ny * w + nx] = cur[1] + grid.cost(nx, ny);
                    pq.add(new int[] {ny * w + nx, dist[ny * w + nx]});
                }
            }
        }
        return dist;
    }
}
