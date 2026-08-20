package com.example.orderfulfillment.inventory;

import com.example.orderfulfillment.InventoryReservationService;
import com.example.orderfulfillment.ReservationResult;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single-instance in-memory stock ledger -- deliberately {@code replicas: 1} (see
 * ../deployment.yaml's own comment): each replica would otherwise hold its own independent copy
 * of {@link #stock}, so a real multi-replica deployment would double-sell the same units, a
 * correctness problem well beyond what this example is about. A real system would back this with
 * a shared, transactional store; this one keeps the mechanism (reserve/release as a genuine
 * compensable step) visible without that added machinery.
 *
 * <p>Seeds one deliberately scarce SKU ({@code gadget}) alongside one effectively unlimited one
 * ({@code widget}) so a real batch run exercises both compensation triggers this app cares about:
 * an outright reservation failure (insufficient stock, no compensation needed since nothing was
 * reserved) and a successful reservation later compensated by a downstream failure.
 */
public final class InventoryReservationHooks
    implements ModuleLifecycleHooks, InventoryReservationService {

  private static final Logger log = LoggerFactory.getLogger(InventoryReservationHooks.class);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final Map<String, int[]> stock =
      new ConcurrentHashMap<>(
          Map.of(
              "widget", new int[] {1_000_000},
              "gadget", new int[] {200}));
  private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(InventoryReservationService.class, this);
    ready.set(true);
    log.info("inventory-reservation registered its InventoryReservationService on the fabric");
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public ReservationResult reserve(String orderId, String sku, int quantity) {
    int[] counter = stock.get(sku);
    if (counter == null) {
      return new ReservationResult(false, null, "unknown sku: " + sku);
    }
    synchronized (counter) {
      if (counter[0] < quantity) {
        return new ReservationResult(false, null, "insufficient stock for sku " + sku);
      }
      counter[0] -= quantity;
    }
    String reservationId = UUID.randomUUID().toString();
    reservations.put(reservationId, new Reservation(sku, quantity));
    return new ReservationResult(true, reservationId, null);
  }

  @Override
  public void release(String reservationId) {
    Reservation reservation = reservations.remove(reservationId);
    if (reservation == null) {
      log.warn("release called for unknown reservationId {} -- ignoring", reservationId);
      return;
    }
    int[] counter = stock.get(reservation.sku());
    if (counter != null) {
      synchronized (counter) {
        counter[0] += reservation.quantity();
      }
    }
    log.info(
        "released reservation {} ({} units of {})",
        reservationId,
        reservation.quantity(),
        reservation.sku());
  }

  private record Reservation(String sku, int quantity) {}
}
