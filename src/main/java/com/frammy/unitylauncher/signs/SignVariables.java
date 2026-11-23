package com.frammy.unitylauncher.signs;

import java.util.List;

public class SignVariables {
    private final boolean isConfigurable;
    private boolean isPaused;
    private final List<Integer> scrollLines;
    private List<String> signText;

    private String ownerName;        // кто поставил (для инфо/логов)
    private final String ownerCountry;     // ВАЖНО: чья табличка (страна canonical)

    private SignState state;
    private final SignCategory category;
    private String markerID;

    // GH #21 п.3: простой sequential ID для игроков (в отличие от markerID —
    // случайного UUID, непригодного для того, чтобы игрок мог его назвать/
    // записать). 0 значит "не назначен" (не-ATM таблички, или старые ATM,
    // созданные до этого поля — им номер не бэкафилливается).
    private int atmNumber;

    public SignVariables(
            String ownerName,
            String ownerCountry,
            List<String> signText,
            List<Integer> scrollLines,
            boolean isConfigurable,
            boolean isPaused,
            SignCategory category,
            SignState state,
            String markerID
    ) {
        this.isConfigurable = isConfigurable;
        this.isPaused = isPaused;
        this.signText = signText;
        this.ownerName = ownerName;
        this.ownerCountry = ownerCountry;
        this.scrollLines = scrollLines;
        this.category = category;
        this.state = state;
        this.markerID = markerID;
    }

    public boolean isConfigurable() {
        return isConfigurable;
    }
    public boolean isPaused() {
        return isPaused;
    }
    public List<String> getSignText() {
        return signText;
    }
    public List<Integer> getScrollLines() {return scrollLines;}
    public SignCategory getSignCategory() {return category;}
    public SignState getSignState() {
        return state;
    }
    public String getOwnerName() { return ownerName; }
    public String getOwnerCountry() { return ownerCountry; }
    public String getMarkerID() {return markerID;}
    public int getAtmNumber() { return atmNumber; }


    public void setPaused(boolean isPaused) {
        this.isPaused = isPaused;
    }
    public void setSignText(List<String> signText) {
        this.signText = signText;
    }

    public void setSignState(SignState state) {
        this.state = state;
    }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public void setMarkerID(String markerID) {
        this.markerID = markerID;
    }
    public void setAtmNumber(int atmNumber) {
        this.atmNumber = atmNumber;
    }
}
