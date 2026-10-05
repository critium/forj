package forj.examples.tagless;

import forj.data.Unit;
import forj.typeclass.Sync;

/**
 * The program, written once against capabilities, not against IO: it needs to sequence and
 * fail (Sync, which brings Monad and MonadError) and the two algebras. Each interpreter of F
 * decides what "stock", "charge" and "run" mean.
 */
public final class Checkout {
    private Checkout() {}

    public static <F<_>> F<Receipt> checkout(Order order) using Sync<F> sync, Inventory<F> inventory, Payments<F> payments {
        return forj {
            available <- inventory.stock(order.item());
            _ <- available >= order.quantity()
                    ? sync.unit()
                    : sync.<Unit>raiseError(new OutOfStock(order.item(), order.quantity(), available));
            _ <- inventory.reserve(order.item(), order.quantity());
            transaction <- payments.charge(order.customer(), order.totalCents());
        } yield new Receipt(order, transaction);
    }
}
