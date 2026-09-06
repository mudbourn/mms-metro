package info.mudbourn.mmsmetro.train;

import info.mudbourn.mmsmetro.config.MetroConfig;
import info.mudbourn.mmsmetro.entity.MetroCarEntity;
import info.mudbourn.mmsmetro.path.PathPoint;
import info.mudbourn.mmsmetro.path.RailPath;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

// An ordered train of cars sharing one resolved path. The lead advances by
// arc-length and every follower sits a fixed distance behind it on that path.
public final class Consist {

    private final List<MetroCarEntity> cars = new ArrayList<>();

    private final RailPath path;

    private final MetroConfig config;

    private double headArc;

    private double speed;

    public Consist(RailPath path, MetroConfig config, double initialHeadArc) {
        this.path = path;
        this.config = config;
        this.headArc = Math.min(initialHeadArc, path.length());
    }

    public void addCar(MetroCarEntity car) {
        this.cars.add(car);
    }

    public List<MetroCarEntity> cars() {
        return this.cars;
    }

    public void placeCars() {
        for (int i = 0; i < this.cars.size(); i++) {
            double arc = Math.max(0.0, this.headArc - i * this.config.carSpacing);
            PathPoint point = this.path.sample(arc);
            MetroCarEntity car = this.cars.get(i);
            car.refreshPositionAndAngles(point.pos().x, point.pos().y, point.pos().z, point.yaw(), point.pitch());
            car.setArcLength((float) arc);
        }
    }

    public void tick() {
        if (this.path.length() <= 0.0) {
            return;
        }

        this.speed = Math.min(this.config.maxSpeed, this.speed + this.config.acceleration);
        this.headArc += this.speed;
        if (this.headArc >= this.path.length()) {
            this.headArc = this.path.length();
            this.speed = 0.0;
        }

        for (int i = 0; i < this.cars.size(); i++) {
            double arc = Math.max(0.0, this.headArc - i * this.config.carSpacing);
            PathPoint point = this.path.sample(arc);
            MetroCarEntity car = this.cars.get(i);
            car.setPosition(point.pos().x, point.pos().y, point.pos().z);
            car.setYaw(point.yaw());
            car.setPitch(point.pitch());
            car.setVelocity(Vec3d.ZERO);
            car.setArcLength((float) arc);
        }
    }

    public boolean isFinished() {
        for (MetroCarEntity car : this.cars) {
            if (!car.isRemoved()) {
                return false;
            }
        }
        return true;
    }

    public void discard() {
        for (MetroCarEntity car : this.cars) {
            car.discard();
        }
    }
}
