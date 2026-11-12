package local.cinemamanager.access;

/** ENTRY opens entry readers; SERVICE also opens service-only readers. Neither grants management. */
public enum StaffRole {
    ENTRY, SERVICE;
    public boolean allows(StaffRole required) { return required != null && (this == SERVICE || required == ENTRY); }
}
