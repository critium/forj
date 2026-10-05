package forj.examples.tagless;

public final class OutOfStock extends Exception {
    public OutOfStock(String item, int wanted, int available) {
        super(s"$item: wanted $wanted, only $available left");
    }
}
