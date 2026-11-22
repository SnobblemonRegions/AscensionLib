package com.cobbleascend.sim;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code ./gradlew :balance-sim:simulate [-PsimSeed=..] [-PsimPlayers=..] [-PsimDays=..] [-PsimSinks=true] [-PsimResearch=true]}: writes report.json and report.md. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "build/balance-sim");
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 20261103L;
        int players = args.length > 2 ? Integer.parseInt(args[2]) : 250;
        int days = args.length > 3 ? Integer.parseInt(args[3]) : 90;
        var model = new Simulation.Model();
        // The optional fifth argument switches the Reclaimer and Wager Altar prototypes on (-PsimSinks=true).
        if (args.length > 4 && Boolean.parseBoolean(args[4])) { model.recycle = true; model.gamble = true; }
        // The optional sixth switches the Field Research prototype on (-PsimResearch=true).
        if (args.length > 5 && Boolean.parseBoolean(args[5])) model.research = true;
        // The optional seventh scales every research grant, in percent (-PsimResearchScale=50), to size the numbers.
        if (args.length > 6) model.researchGrantPercent = Integer.parseInt(args[6]);
        Simulation sim = new Simulation(seed, players, days, model).run();
        SimReport report = new SimReport(sim);
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.json"), report.json(), StandardCharsets.UTF_8);
        String md = report.markdown(sim);
        Files.writeString(out.resolve("report.md"), md, StandardCharsets.UTF_8);
        System.out.println(md);
    }
}
