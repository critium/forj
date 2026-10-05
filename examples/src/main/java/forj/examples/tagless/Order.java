package forj.examples.tagless;

public record Order(String customer, String item, int quantity, long unitCents) {
    public long totalCents() {
        return unitCents * quantity;
    }
}
