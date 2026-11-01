package com.cobbleascend.sim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code ./gradlew :balance-sim:simulate [-PsimSeed=..] [-PsimPlayers=..] [-PsimDays=..]}: writes report.json and report.md. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/balance-sim");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261103L;
        int players = args.length > 2 ? Integer.parseInt(args[2]) : 250;
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 90;
        Simulation sim = new Simulation(seed, players, days, new Simulation.Model()).run();
        SimReport report = new SimReport(sim);
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.json"), report.json(), StandardCharsets.UTF_8);
        String md = report.markdown(sim);
        Files.writeString(out.resolve("report.md"), md, StandardCharsets.UTF_8);
        System.out.println(md);
    }
}
