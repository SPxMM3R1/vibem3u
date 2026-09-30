package cl.streambox.tv;

/** Lo que MainActivity necesita de la Guía, sea la moderna o la clásica. */
interface GuideSurface {
    void bind(EpgGuideView.Source source, EpgGuideNavigator navigator, long nowMillis);

    void refresh(long nowMillis);
}
