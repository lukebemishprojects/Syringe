package dev.lukebemish.syringe.test;

import dev.lukebemish.syringe.Binds;
import dev.lukebemish.syringe.Component;
import dev.lukebemish.syringe.ObjectFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import junit.framework.Test;
import org.atinject.tck.Tck;
import org.atinject.tck.auto.Car;
import org.atinject.tck.auto.Convertible;
import org.atinject.tck.auto.Drivers;
import org.atinject.tck.auto.DriversSeat;
import org.atinject.tck.auto.Engine;
import org.atinject.tck.auto.Seat;
import org.atinject.tck.auto.Tire;
import org.atinject.tck.auto.V8Engine;
import org.atinject.tck.auto.accessories.SpareTire;

public class BootstrapTCK {
    public static Test suite() {
        var component = ObjectFactory.create().instance(TckComponent.class);
        Car tckCar = component.getObjectFactory().instance(Car.class);
        return Tck.testsFor(tckCar, false, true);
    }

    @Component
    public static abstract class TckComponent {
        protected abstract @Inject ObjectFactory getObjectFactory();

        protected abstract @Binds Car makeCar(Convertible car);

        protected abstract @Binds @Drivers Seat makeSeat(DriversSeat seat);

        protected abstract @Binds Engine makeEngine(V8Engine engine);

        protected abstract @Binds @Named("spare") Tire makeTire(SpareTire tire);
    }
}
