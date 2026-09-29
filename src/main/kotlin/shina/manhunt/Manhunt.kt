package shina.manhunt

import org.bukkit.GameRules
import org.bukkit.plugin.java.JavaPlugin
import shina.manhunt.game.ManhuntCommand
import shina.manhunt.game.Session
import shina.manhunt.play.GameListener
import shina.manhunt.play.HunterLoop
import shina.manhunt.play.Ticker
import shina.manhunt.progress.Progress

class Manhunt : JavaPlugin() {
    lateinit var session: Session
    lateinit var progress: Progress
    lateinit var hunterLoop: HunterLoop
    lateinit var ticker: Ticker

    override fun onEnable() {
        instance = this
        session = Session(this)
        progress = Progress(this)
        hunterLoop = HunterLoop(this)
        ticker = Ticker(this)
        progress.install()
        server.worlds.forEach { it.setGameRule(GameRules.LOCATOR_BAR, false) }
        registerCommand("manhunt", "猎人游戏", ManhuntCommand(this))
        server.pluginManager.registerEvents(GameListener(this), this)
        server.scheduler.runTaskTimer(this, Runnable { ticker.tick() }, 20L, 20L)
    }

    override fun onDisable() {
        if (::ticker.isInitialized) ticker.hideBoard()
    }

    companion object {
        lateinit var instance: Manhunt
    }
}
