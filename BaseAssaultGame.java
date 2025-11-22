import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * BaseAssaultGame
 * A 2D side-scrolling action/strategy hybrid game based on 8-bit aesthetic.
 * This class handles the main game window and frame setup.
 */
public class BaseAssaultGame extends JFrame {

    // FIX: Changed to public static final for access by nested classes.
    public static final int GAME_WIDTH = 800;
    public static final int GAME_HEIGHT = 450;

    public BaseAssaultGame() {
        setTitle("Base Assault: Strategic Blitz (Java Edition)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        GamePanel gamePanel = new GamePanel();
        gamePanel.setPreferredSize(new Dimension(GAME_WIDTH, GAME_HEIGHT));
        add(gamePanel, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(null); // Center the window
        setVisible(true);

        gamePanel.startGameLoop();
    }

    public static void main(String[] args) {
        // Run the game setup on the Event Dispatch Thread
        SwingUtilities.invokeLater(BaseAssaultGame::new);
    }
}

/**
 * GamePanel handles all game logic, updates, and rendering.
 */
class GamePanel extends JPanel implements KeyListener, Runnable {

    // --- Constants ---
    private static final int GAME_FPS = 15; // Target FPS for 8-bit update speed
    private static final long FRAME_DELAY = 1000 / GAME_FPS; // Delay in milliseconds
    private static final int SCALING = 4; // Main pixel scaling factor
    // Access is now correct due to constants being public in BaseAssaultGame
    private static final int GROUND_Y = BaseAssaultGame.GAME_HEIGHT - 30;
    private static final double GRAVITY = 0.5;
    private static final Font PIXEL_FONT = new Font("Monospaced", Font.BOLD, SCALING * 3); // Scaled font

    // --- Game State ---
    private volatile boolean gameActive = true;
    private Thread gameThread;
    private Hero hero;
    private Base playerBase;
    private Base enemyBase;
    private final ArrayList<Unit> friendlyUnits = new ArrayList<>();
    private final ArrayList<Unit> enemyUnits = new ArrayList<>();
    private final ArrayList<Projectile> projectiles = new ArrayList<>();
    private final ArrayList<Pickup> pickups = new ArrayList<>();
    private final boolean[] keys = new boolean[256]; // Key state array
    private long enemySpawnTimer = 0;
    private long friendlySpawnTimer = 0;
    private int coins = 0;
    private int respawns = 3;
    private final Random random = new Random();

    public GamePanel() {
        setFocusable(true);
        addKeyListener(this);
        setBackground(new Color(135, 206, 235)); // Light blue sky color
        initGame();
    }

    private void initGame() {
        // Access is now correct due to constants being public in BaseAssaultGame
        playerBase = new Base(SCALING * 2, GROUND_Y - SCALING * 25, SCALING * 25, SCALING * 25, true, 100);
        enemyBase = new Base(BaseAssaultGame.GAME_WIDTH - (SCALING * 27), GROUND_Y - SCALING * 25, SCALING * 25, SCALING * 25, false, 1000);
        hero = new Hero(playerBase.x + 50, GROUND_Y - (SCALING * 8));
    }

    public void startGameLoop() {
        gameThread = new Thread(this);
        gameThread.start();
    }

    @Override
    public void run() {
        long lastTime = System.currentTimeMillis();

        while (gameActive) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastTime;
            
            // Only update game state if enough time has passed for one frame
            if (elapsed >= FRAME_DELAY) {
                updateGame(elapsed / (double) FRAME_DELAY);
                lastTime = now; 
            }

            repaint();

            // Sleep to control frame rate (if update was fast)
            try {
                long sleepTime = FRAME_DELAY - (System.currentTimeMillis() - now);
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void updateGame(double delta) {
        if (!gameActive) return;

        // 1. Update Hero
        hero.update(delta);

        // 2. Update Units
        friendlyUnits.forEach(u -> u.update(delta));
        enemyUnits.forEach(u -> u.update(delta));

        // 3. Update Projectiles
        Iterator<Projectile> projIterator = projectiles.iterator();
        while (projIterator.hasNext()) {
            Projectile p = projIterator.next();
            p.update(delta);
            // Access is now correct due to constants being public in BaseAssaultGame
            if (p.x < -10 || p.x > BaseAssaultGame.GAME_WIDTH + 10) {
                projIterator.remove();
            }
        }

        // 4. Update Pickups
        pickups.forEach(p -> p.update(delta));

        // 5. Check Collisions and Damage
        checkCollisions();
        
        // 6. Spawning and Base Logic
        handleSpawning();
        handleBaseLogic();

        // 7. Win/Loss Check
        checkWinLoss();
    }

    // --- Input Handling ---

    // Key mapping: A=65, D=68, W=87, S=83, Space=32, Arrow Keys (check keyCode)
    private static final int KEY_A = 65, KEY_D = 68, KEY_W = 87, KEY_S = 83;
    
    @Override
    public void keyPressed(KeyEvent e) {
        keys[e.getKeyCode()] = true;
    }

    @Override
    public void keyReleased(KeyEvent e) {
        keys[e.getKeyCode()] = false;
    }

    @Override
    public void keyTyped(KeyEvent e) {
        // Not used
    }

    // --- Entity Classes (Nested) ---
    
    abstract class Entity {
        double x, y, vy;
        int size;
        int maxHp, hp;
        int width, height; // For Base

        public Rectangle getBounds() {
            // FIX: Explicitly cast double to int
            return new Rectangle((int) x, (int) y, size, size);
        }

        public abstract void update(double delta);
        public abstract void draw(Graphics g);
        public abstract boolean takeDamage(int damage);
    }
    
    class Base extends Entity {
        boolean isPlayer;
        Color color;
        boolean tower1Enabled = true; // For respawn penalty
        long towerDisableStartTime = 0;
        static final long TOWER_DISABLE_DURATION = 5000; // 5 seconds

        public Base(int x, int y, int width, int height, boolean isPlayer, int maxHp) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.isPlayer = isPlayer;
            this.maxHp = maxHp;
            this.hp = maxHp;
            this.color = new Color(100, 100, 100); // Concrete/Bunker color
            this.size = width; // Base size is width for bounds check
        }
        
        @Override
        public Rectangle getBounds() {
            // FIX: Explicitly cast double to int
            return new Rectangle((int) x, (int) y, width, height);
        }
        
        @Override
        public void update(double delta) {
            if (!tower1Enabled) {
                if (System.currentTimeMillis() - towerDisableStartTime >= TOWER_DISABLE_DURATION) {
                    tower1Enabled = true;
                }
            }

            // Simple Tower Fire Logic
            if (random.nextDouble() < 0.01) {
                towerFire();
            }
        }

        public void disableTower() {
            tower1Enabled = false;
            towerDisableStartTime = System.currentTimeMillis();
        }

        @Override
        public boolean takeDamage(int damage) {
            hp -= damage;
            if (hp < 0) hp = 0;
            return hp <= 0;
        }

        @Override
        public void draw(Graphics g) {
            // Draw a simulated 8-bit bunker
            g.setColor(new Color(100, 100, 100)); // Dark Gray Base
            g.fillRect((int) x, (int) y, width, height);
            
            // Add blocky shading
            g.setColor(new Color(120, 120, 120));
            g.fillRect((int) x, (int) y, SCALING, height);
            g.fillRect((int) x, (int) y, width, SCALING);
            
            // Entrance/Window
            g.setColor(Color.BLACK);
            g.fillRect((int) x + SCALING * 5, (int) y + SCALING * 10, SCALING * 15, SCALING * 15);
            
            // Recovery Zone Indicator
            if (isPlayer) {
                g.setColor(new Color(0, 255, 0, 150)); // Bright Green semi-transparent
                g.fillRect((int) x + SCALING * 2, (int) y + SCALING * 2, width - SCALING * 4, height - SCALING * 4);
                g.setColor(Color.WHITE);
                g.setFont(PIXEL_FONT.deriveFont(Font.PLAIN, SCALING * 2));
                g.drawString("HEAL", (int) x + SCALING * 9, (int) y + SCALING * 12);
            }
            
            // Tower (Top-left)
            g.setColor(Color.DARK_GRAY);
            g.fillRect((int) x + SCALING * 2, (int) y - SCALING * 4, SCALING * 5, SCALING * 4);
            g.setColor(tower1Enabled ? Color.RED : Color.GRAY); // Tower 1 status light
            g.fillRect((int) x + SCALING * 3, (int) y - SCALING * 3, SCALING * 3, SCALING * 2);

            // Tower 2 (Top-right)
            g.setColor(Color.DARK_GRAY);
            g.fillRect((int) x + width - SCALING * 7, (int) y - SCALING * 4, SCALING * 5, SCALING * 4);
            g.setColor(Color.RED); // Tower 2 always active light
            g.fillRect((int) x + width - SCALING * 6, (int) y - SCALING * 3, SCALING * 3, SCALING * 2);
        }
    }

    class Hero extends Entity {
        int direction = 1; // 1: right, -1: left
        double speed = SCALING * 1.5;
        int fireTimer = 0;
        int fireRate = 10;
        long invincibility = 0;
        long invincibilityStartTime = 0;
        boolean isJumping = false;
        boolean isCrouching = false;
        boolean isHealing = false;
        long lastHealTime = System.currentTimeMillis();
        static final long HEAL_INTERVAL = 1500; // 1 HP per 1.5 seconds

        public Hero(int x, int y) {
            this.x = x;
            this.y = y;
            this.size = SCALING * 8; // Hero size is 32x32 based on SCALING
            this.maxHp = 5;
            this.hp = 5;
        }

        @Override
        public void update(double delta) {
            // Movement
            if (keys[KEY_A] || keys[KeyEvent.VK_LEFT]) { this.x -= speed * delta; this.direction = -1; }
            if (keys[KEY_D] || keys[KeyEvent.VK_RIGHT]) { this.x += speed * delta; this.direction = 1; }

            // Access is now correct due to constants being public in BaseAssaultGame
            this.x = Math.max(0, Math.min(this.x, BaseAssaultGame.GAME_WIDTH - this.size));

            // Gravity and Jumping
            vy += GRAVITY * delta;
            y += vy * delta;
            if (y >= GROUND_Y - size) {
                y = GROUND_Y - size;
                vy = 0;
                isJumping = false;
            }

            if ((keys[KEY_W] || keys[KeyEvent.VK_UP]) && !isJumping) {
                vy = -SCALING * 5;
                isJumping = true;
            }

            isCrouching = (keys[KEY_S] || keys[KeyEvent.VK_DOWN]);
            if (isCrouching) {
                this.y = GROUND_Y - SCALING * 6; // Lower the hero slightly
                this.size = SCALING * 6; // Reduce effective height
            } else {
                this.size = SCALING * 8; // Restore full height
            }

            // Firing
            if (keys[KeyEvent.VK_SPACE] && fireTimer <= 0) {
                fire();
                fireTimer = fireRate;
                isHealing = false; // Interrupt healing
            }
            fireTimer = Math.max(0, fireTimer - 1);

            // Invincibility Frames
            if (invincibility > 0 && System.currentTimeMillis() - invincibilityStartTime > invincibility) {
                invincibility = 0;
            }

            handleHealing();
        }

        private void handleHealing() {
            // FIX: Explicitly cast double to int for Rectangle constructor
            Rectangle recoveryZone = new Rectangle((int)playerBase.x, (int)playerBase.y, playerBase.width, playerBase.height);

            boolean insideZone = getBounds().intersects(recoveryZone);
            boolean isMoving = (keys[KEY_A] || keys[KEY_D] || keys[KeyEvent.VK_LEFT] || keys[KeyEvent.VK_RIGHT]);
            
            if (insideZone && !isMoving && hp < maxHp) {
                isHealing = true;
                if (System.currentTimeMillis() - lastHealTime >= HEAL_INTERVAL) {
                    hp = Math.min(maxHp, hp + 1);
                    lastHealTime = System.currentTimeMillis();
                }
            } else {
                isHealing = false;
                lastHealTime = System.currentTimeMillis();
            }
        }

        private void fire() {
            int bulletX = (int) (x + (direction == 1 ? size : 0));
            // Adjust y position slightly for a more "gun-like" fire position
            projectiles.add(new Projectile(bulletX, (int) y + size * 3 / 4, direction, true));
        }

        @Override
        public boolean takeDamage(int damage) {
            if (invincibility == 0) {
                hp -= damage;
                invincibility = 2000; // 2 seconds invincibility
                invincibilityStartTime = System.currentTimeMillis();
                
                if (hp <= 0) {
                    die();
                    return true;
                }
            }
            return false;
        }

        private void die() {
            respawns--;
            if (respawns >= 0) {
                // Respawn Penalty
                coins = 0; 
                playerBase.disableTower();
                
                // Respawn at base
                hp = maxHp;
                x = playerBase.x + 50;
                y = GROUND_Y - (SCALING * 8);
                vy = 0;
                invincibility = 4000; 
                invincibilityStartTime = System.currentTimeMillis();
            } else {
                gameActive = false;
            }
        }
        
        public void applyPickup(String type) {
            switch(type) {
                case "COIN": coins += 10; break;
                case "HEALTH": hp = Math.min(maxHp, hp + 1); break;
                case "WEAPON": fireRate = 5; new Timer(5000, e -> fireRate = 10).start(); break;
                case "SHIELD": invincibility = 5000; invincibilityStartTime = System.currentTimeMillis(); break;
            }
        }

        @Override
        public void draw(Graphics g) {
            drawHeroSprite(g);
            
            // Draw Health Bar (Pixel Style)
            int barWidth = SCALING * 10;
            int barHeight = SCALING * 2;
            int barX = (int) x + size / 2 - barWidth / 2;
            int barY = (int) y - SCALING * 4;

            double hpPercent = (double) hero.hp / hero.maxHp;

            // Health Bar Background (Dark Frame)
            g.setColor(Color.BLACK);
            g.fillRect(barX - 1, barY - 1, barWidth + 2, barHeight + 2);
            
            // Health Bar Fill
            g.setColor(Color.RED);
            g.fillRect(barX, barY, barWidth, barHeight);
            g.setColor(Color.GREEN);
            g.fillRect(barX, barY, (int) (barWidth * hpPercent), barHeight);
        }
        
        private void drawHeroSprite(Graphics g) {
            // Flash if invulnerable
            boolean isInvincibleFlashing = invincibility > 0 && (System.currentTimeMillis() - invincibilityStartTime) % 200 < 100;
            
            Color heroColor = isInvincibleFlashing ? new Color(255, 136, 136) : (isHealing ? Color.GREEN.darker() : new Color(50, 50, 50)); // Dark uniform

            if (isCrouching) {
                // Crouch Sprite
                g.setColor(heroColor);
                g.fillRect((int) x, (int) y + SCALING * 2, SCALING * 8, SCALING * 4); // Body block
                g.setColor(Color.RED); // Head/Helmet
                g.fillRect((int) x + SCALING * 1, (int) y, SCALING * 4, SCALING * 2);

                // Gun (Pistol-like)
                g.setColor(Color.BLACK);
                int gunY = (int) y + SCALING * 4;
                if (direction == 1) {
                    g.fillRect((int) x + SCALING * 6, gunY, SCALING * 3, SCALING * 1);
                } else {
                    g.fillRect((int) x - SCALING * 1, gunY, SCALING * 3, SCALING * 1);
                }
            } else {
                // Standing Sprite (8x8 pixel size)
                
                // Helmet/Hair (4x4)
                g.setColor(new Color(170, 80, 0)); // Helmet Color
                g.fillRect((int) x + SCALING * 2, (int) y + SCALING * 1, SCALING * 4, SCALING * 3);

                // Body (6x6)
                g.setColor(heroColor);
                g.fillRect((int) x + SCALING * 1, (int) y + SCALING * 4, SCALING * 6, SCALING * 4);
                
                // Gun (4x1)
                g.setColor(Color.DARK_GRAY);
                int gunY = (int) y + SCALING * 5;
                if (direction == 1) { // Facing right
                    g.fillRect((int) x + SCALING * 7, gunY, SCALING * 4, SCALING * 1);
                } else { // Facing left
                    g.fillRect((int) x - SCALING * 3, gunY, SCALING * 4, SCALING * 1);
                }
            }
        }
    }

    class Unit extends Entity {
        boolean isFriendly;
        int moveDirection;
        Color bodyColor;
        double moveSpeed = 1.0;
        int fireRate = 45;
        int fireTimer = 0;
        boolean isElite = false;
        boolean isHeavy = false;

        public Unit(int x, int y, boolean isFriendly, int hp, boolean isElite, boolean isHeavy) {
            this.x = x;
            this.y = y;
            this.size = SCALING * 6; // Unit size 24x24
            this.isFriendly = isFriendly;
            this.moveDirection = isFriendly ? 1 : -1;
            this.maxHp = hp;
            this.hp = hp;
            this.isElite = isElite;
            this.isHeavy = isHeavy;
            this.bodyColor = isFriendly ? new Color(10, 10, 150) : new Color(150, 10, 10);
            
            if (isElite) {
                this.bodyColor = new Color(200, 50, 200); // Elite Purple
            } else if (isHeavy) {
                this.bodyColor = new Color(100, 100, 100); // Heavy Gray
                this.moveSpeed = 0.5; // Slower
                this.fireRate = 20; // Faster fire
                this.size = SCALING * 8; // Larger size
            }
        }

        @Override
        public void update(double delta) {
            // Simple AI: move towards target base
            x += moveDirection * moveSpeed * delta; 
            
            // Combat: fire periodically
            if (fireTimer <= 0) {
                if (isFriendly && x > playerBase.x + 200) {
                    fire();
                    fireTimer = fireRate;
                } else if (!isFriendly && x < enemyBase.x - 200) {
                    fire();
                    fireTimer = fireRate;
                }
            }
            fireTimer = Math.max(0, fireTimer - 1);
        }

        private void fire() {
            int projDamage = isHeavy ? 2 : 1; // Heavy units deal more damage
            projectiles.add(new Projectile((int) x + size / 2, (int) y + size * 3 / 4, moveDirection, isFriendly, projDamage));
        }

        @Override
        public boolean takeDamage(int damage) {
            hp -= damage;
            if (hp <= 0) {
                die();
                return true;
            }
            return false;
        }

        private void die() {
            if (isElite || random.nextDouble() < 0.1) { 
                spawnPickup((int) x, (int) y);
            }
        }

        @Override
        public void draw(Graphics g) {
            // Draw a simulated unit sprite
            
            // Body 
            g.setColor(bodyColor);
            g.fillRect((int) x + SCALING * 1, (int) y + SCALING * 2, size - SCALING * 2, size - SCALING * 2);
            
            // Head 
            g.setColor(isFriendly ? Color.YELLOW : Color.WHITE);
            g.fillRect((int) x + SCALING * 2, (int) y, size - SCALING * 4, SCALING * 2);
            
            // Weapon (Machine Gun for Heavy, Rifle for others)
            g.setColor(Color.BLACK);
            int gunY = (int) y + size / 2;
            int gunLength = isHeavy ? SCALING * 4 : SCALING * 2;
            
            if (moveDirection == 1) { // Facing right
                 g.fillRect((int) x + size - SCALING * 1, gunY, gunLength, SCALING * 1);
            } else { // Facing left
                 g.fillRect((int) x - gunLength + SCALING * 1, gunY, gunLength, SCALING * 1);
            }

            // Draw HP Bar 
            double hpPercent = (double) hp / maxHp;
            int barWidth = size;
            g.setColor(Color.BLACK);
            g.fillRect((int) x, (int) y - SCALING * 2, barWidth, SCALING);
            
            Color hpColor = (hpPercent > 0.5) ? Color.GREEN : (hpPercent > 0.2) ? Color.YELLOW : Color.RED;
            g.setColor(hpColor);
            g.fillRect((int) x, (int) y - SCALING * 2, (int) (barWidth * hpPercent), SCALING);
        }
    }
    
    // Concrete unit classes for clarity
    class InfantryUnit extends Unit {
        public InfantryUnit(int x, int y) {
            super(x, y, false, 1, false, false);
        }
    }
    
    class EliteUnit extends Unit {
        public EliteUnit(int x, int y) {
            super(x, y, false, 3, true, false);
        }
    }
    
    class HeavyMachineGunner extends Unit {
        public HeavyMachineGunner(int x, int y) {
            super(x, y, false, 5, false, true); // Higher HP (5), Slower, Faster Fire
        }
    }


    class Projectile extends Entity {
        boolean isFriendly;
        int direction;
        int damage;
        Color color;
        double speed = SCALING * 3;

        public Projectile(int x, int y, int direction, boolean isFriendly) {
            this(x, y, direction, isFriendly, isFriendly ? 10 : 1);
        }
        
        public Projectile(int x, int y, int direction, boolean isFriendly, int baseDamage) {
            this.x = x;
            this.y = y;
            this.direction = direction;
            this.isFriendly = isFriendly;
            this.damage = baseDamage;
            this.size = SCALING * 1;
            this.color = isFriendly ? new Color(255, 200, 0) : Color.RED; // Gold/Yellow for hero shots
        }

        @Override
        public void update(double delta) {
            x += direction * speed * delta;
        }

        @Override
        public void draw(Graphics g) {
            g.setColor(color);
            // Draw as a small, bright rectangle for a projectile
            g.fillRect((int) x, (int) y, size, size);
        }

        @Override
        public boolean takeDamage(int damage) { return false; }
    }

    class Pickup extends Entity {
        String type;
        Color color;
        
        public Pickup(int x, int y, String type) {
            this.x = x;
            this.y = y;
            this.size = SCALING * 4;
            this.type = type;
            this.vy = -5; // Initial upward jump

            switch(type) {
                case "COIN": this.color = Color.YELLOW; break;
                case "HEALTH": this.color = new Color(0, 200, 0); break; // Green Cross
                case "WEAPON": this.color = Color.BLUE; break;
                case "SHIELD": this.color = Color.CYAN; break;
            }
        }

        @Override
        public void update(double delta) {
            vy += GRAVITY * delta;
            y += vy * delta;
            if (y >= GROUND_Y - size) {
                y = GROUND_Y - size;
                vy = 0;
            }
        }

        @Override
        public void draw(Graphics g) {
            // Draw a blocky icon based on type
            g.setColor(Color.BLACK);
            g.fillRect((int) x, (int) y, size, size); // Black outline
            
            g.setColor(color);
            int innerSize = SCALING * 3;
            g.fillRect((int) x + SCALING / 2, (int) y + SCALING / 2, innerSize, innerSize);

            // Add detail for health/weapon/shield
            if (type.equals("HEALTH")) {
                g.setColor(Color.WHITE);
                g.fillRect((int) x + SCALING, (int) y + SCALING * 2, SCALING * 2, SCALING);
                g.fillRect((int) x + SCALING * 2, (int) y + SCALING, SCALING, SCALING * 2);
            }
        }

        @Override
        public boolean takeDamage(int damage) { return false; }
    }


    // --- Game Logic Methods ---
    
    private void towerFire() {
        // Player Base defensive fire
        Unit friendlyTarget = enemyUnits.stream().findFirst().orElse(null);
        if (playerBase.tower1Enabled && friendlyTarget != null) {
            // FIX: Explicitly cast double to int
            projectiles.add(new Projectile((int)(playerBase.x + SCALING * 4), (int)(playerBase.y - SCALING * 2), 1, true, 10));
        }

        // Enemy Base defensive fire
        Unit enemyTarget = friendlyUnits.stream().findFirst().orElse(null); // FIX: Removed hero fallback
        if (enemyTarget != null) {
            // FIX: Explicitly cast double to int
            projectiles.add(new Projectile((int)(enemyBase.x + enemyBase.width - SCALING * 4), (int)(enemyBase.y - SCALING * 2), -1, false, 1));
        }
    }
    
    private void spawnPickup(int x, int y) {
        final double roll = random.nextDouble() * 100;
        String type;

        if (roll < 60) type = "COIN";
        else if (roll < 90) type = "HEALTH";
        else if (roll < 98) type = "WEAPON";
        else type = "SHIELD";

        pickups.add(new Pickup(x, y, type));
    }

    private void handleSpawning() {
        enemySpawnTimer++;
        if (enemySpawnTimer > 120) { // Enemy spawn rate
            double roll = random.nextDouble();
            Unit newUnit;
            
            // FIX: Explicitly cast double to int for constructor
            if (roll < 0.1) { // 10% Heavy Machine Gunner
                newUnit = new HeavyMachineGunner((int)(enemyBase.x - SCALING * 8), (int)(GROUND_Y - SCALING * 8)); // Larger size needs lower Y offset
            } else if (roll < 0.3) { // 20% Elite Trooper (0.1 to 0.3)
                newUnit = new EliteUnit((int)(enemyBase.x - SCALING * 8), (int)(GROUND_Y - SCALING * 6));
            } else { // 70% Infantry Unit (0.3 to 1.0)
                newUnit = new InfantryUnit((int)(enemyBase.x - SCALING * 8), (int)(GROUND_Y - SCALING * 6));
            }
            
            enemyUnits.add(newUnit);
            enemySpawnTimer = 0;
        }

        friendlySpawnTimer++;
        if (friendlySpawnTimer > 300) { // Friendly spawn rate
            // FIX: Explicitly cast double to int for constructor
            friendlyUnits.add(new Unit((int)(playerBase.x + playerBase.width + SCALING * 2), (int)(GROUND_Y - SCALING * 6), true, 2, false, false));
            friendlySpawnTimer = 0;
        }
    }

    private void handleBaseLogic() {
        // Nothing complex here yet, but this is where base-specific update logic would go.
    }

    private void checkCollisions() {
        // Projectile vs Hero/Units/Bases
        Iterator<Projectile> projIterator = projectiles.iterator();
        while (projIterator.hasNext()) {
            Projectile p = projIterator.next();
            boolean hit = false;
            
            if (!p.isFriendly && p.getBounds().intersects(hero.getBounds())) {
                hero.takeDamage(p.damage); hit = true;
            }

            if (p.isFriendly) {
                Iterator<Unit> enemyIterator = enemyUnits.iterator();
                while (enemyIterator.hasNext()) {
                    Unit e = enemyIterator.next();
                    if (p.getBounds().intersects(e.getBounds())) {
                        if (e.takeDamage(p.damage)) { enemyIterator.remove(); }
                        hit = true; break;
                    }
                }
            }

            // Hero Projectile vs Enemy Base (Any hit causes damage)
            if (p.isFriendly && p.damage == 10) { 
                 if (p.getBounds().intersects(enemyBase.getBounds())) {
                     enemyBase.takeDamage(1); 
                     hit = true;
                 }
            }

            if (hit) {
                projIterator.remove();
            }
        }

        // Hero vs Pickups
        Iterator<Pickup> pickupIterator = pickups.iterator();
        while (pickupIterator.hasNext()) {
            Pickup p = pickupIterator.next();
            if (p.getBounds().intersects(hero.getBounds())) {
                hero.applyPickup(p.type);
                pickupIterator.remove();
            }
        }
        
        // Unit vs Base (Win/Loss condition)
        Iterator<Unit> enemyIterator = enemyUnits.iterator();
        while (enemyIterator.hasNext()) {
            Unit e = enemyIterator.next();
            if (e.x < playerBase.x + playerBase.width) {
                playerBase.takeDamage(10); 
                enemyIterator.remove(); 
            }
        }

        Iterator<Unit> friendlyIterator = friendlyUnits.iterator();
        while (friendlyIterator.hasNext()) {
            Unit f = friendlyIterator.next();
            if (f.x > enemyBase.x - f.size) {
                enemyBase.takeDamage(20); 
                friendlyIterator.remove(); 
            }
        }
    }

    private void checkWinLoss() {
        if (!gameActive) return;

        if (playerBase.hp <= 0) {
            gameActive = false;
            // Use JOptionPane for a simple Game Over screen as a modal
            JOptionPane.showMessageDialog(this, "DEFEAT - Player Base Destroyed!", "Game Over", JOptionPane.INFORMATION_MESSAGE);
            // No graceful way to restart in a simple JFrame, typically the app would close
        } else if (enemyBase.hp <= 0) {
            gameActive = false;
            JOptionPane.showMessageDialog(this, "VICTORY - Enemy Base Destroyed!", "Game Over", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // --- Rendering ---
    
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        
        // Anti-aliasing off for 8-bit look
        ((Graphics2D)g).setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        
        // 1. Draw Simulated Pixel Background (Mountains/Sky)
        drawBackground(g);

        // 2. Draw Bases
        playerBase.draw(g);
        enemyBase.draw(g);

        // 3. Draw Ground/Foreground
        drawGround(g);

        // 4. Draw Pickups
        pickups.forEach(p -> p.draw(g));
        
        // 5. Draw Units
        friendlyUnits.forEach(u -> u.draw(g));
        enemyUnits.forEach(u -> u.draw(g));

        // 6. Draw Hero
        hero.draw(g);

        // 7. Draw Projectiles
        projectiles.forEach(p -> p.draw(g));

        // 8. Draw HUD Overlay (Last to ensure it's on top)
        drawHUD(g);
    }
    
    private void drawBackground(Graphics g) {
        // Sky is set in setBackground (light blue)
        
        // Simulated Parallax Layer 1: Distant Mountains (Dark Green/Blue)
        g.setColor(new Color(30, 60, 40));
        // Access is now correct due to constants being public in BaseAssaultGame
        int[] xPoints1 = {0, BaseAssaultGame.GAME_WIDTH / 4, BaseAssaultGame.GAME_WIDTH / 2, BaseAssaultGame.GAME_WIDTH * 3 / 4, BaseAssaultGame.GAME_WIDTH, BaseAssaultGame.GAME_WIDTH, 0};
        int[] yPoints1 = {GROUND_Y - 150, GROUND_Y - 200, GROUND_Y - 180, GROUND_Y - 220, GROUND_Y - 100, GROUND_Y, GROUND_Y};
        g.fillPolygon(xPoints1, yPoints1, xPoints1.length);
        
        // Simulated Parallax Layer 2: Closer Hills (Medium Green)
        g.setColor(new Color(60, 90, 70));
        // Access is now correct due to constants being public in BaseAssaultGame
        int[] xPoints2 = {0, BaseAssaultGame.GAME_WIDTH / 6, BaseAssaultGame.GAME_WIDTH / 3, BaseAssaultGame.GAME_WIDTH * 2 / 3, BaseAssaultGame.GAME_WIDTH, BaseAssaultGame.GAME_WIDTH, 0};
        int[] yPoints2 = {GROUND_Y - 80, GROUND_Y - 120, GROUND_Y - 100, GROUND_Y - 130, GROUND_Y - 70, GROUND_Y, GROUND_Y};
        g.fillPolygon(xPoints2, yPoints2, xPoints2.length);
    }
    
    private void drawGround(Graphics g) {
        // Draw Ground (Darker Earth)
        g.setColor(new Color(100, 70, 40));
        // Access is now correct due to constants being public in BaseAssaultGame
        g.fillRect(0, GROUND_Y, BaseAssaultGame.GAME_WIDTH, 30);
        
        // Draw Grassy Foreground (Brown/Yellow Stalks like in the image)
        g.setColor(new Color(180, 150, 70));
        // Access is now correct due to constants being public in BaseAssaultGame
        for (int i = 0; i < BaseAssaultGame.GAME_WIDTH; i += SCALING * 2) {
            g.drawLine(i, GROUND_Y, i + SCALING, GROUND_Y - SCALING * 3 - random.nextInt(SCALING * 2));
        }
    }

    private void drawHUD(Graphics g) {
        g.setFont(PIXEL_FONT);

        // --- HUD Frame Top Left (Mimicking the health/score box) ---
        int hudX = 5;
        int hudY = 5;
        int hudWidth = SCALING * 50; // 200px wide
        int hudHeight = SCALING * 8; // 32px high
        
        // Main Box Background and Border
        g.setColor(new Color(50, 50, 50, 200)); // Dark semi-transparent background
        g.fillRect(hudX, hudY, hudWidth, hudHeight);
        g.setColor(Color.WHITE); // White border
        g.drawRect(hudX, hudY, hudWidth, hudHeight);
        
        // 1. Hero Health Bar
        int healthX = hudX + SCALING * 10;
        int healthY = hudY + SCALING * 2;
        int healthBarWidth = SCALING * 20;
        int healthBarHeight = SCALING * 4;
        double hpPercent = (double) hero.hp / hero.maxHp;

        // Draw Health Text
        g.setColor(Color.WHITE);
        g.drawString("HP x" + hero.hp, hudX + SCALING * 1, hudY + SCALING * 5);

        // Draw Health Bar
        g.setColor(new Color(150, 0, 0)); // Red base
        g.fillRect(healthX, healthY, healthBarWidth, healthBarHeight);
        g.setColor(new Color(255, 170, 0)); // Orange/Yellow fill
        g.fillRect(healthX, healthY, (int) (healthBarWidth * hpPercent), healthBarHeight);

        // 2. Coins Display
        int coinX = healthX + healthBarWidth + SCALING * 2;
        g.setColor(Color.YELLOW);
        g.drawString("C " + String.format("%04d", coins), coinX, hudY + SCALING * 5);

        // 3. Lives/Respawn Count
        int respawnX = coinX + SCALING * 15;
        g.setColor(Color.CYAN);
        g.drawString("R " + respawns, respawnX, hudY + SCALING * 5);
        
        // --- Base Health Info (Bottom Left) ---
        g.setFont(PIXEL_FONT.deriveFont(Font.BOLD, SCALING * 4));
        g.setColor(Color.WHITE);
        
        int baseHealthX = 10;
        // Access is now correct due to constants being public in BaseAssaultGame
        int baseHealthY = BaseAssaultGame.GAME_HEIGHT - 10;
        
        // Player Base Health
        g.setColor(new Color(0, 200, 0));
        g.drawString("PLAYER BASE: " + playerBase.hp, baseHealthX, baseHealthY);

        // Enemy Base Health
        g.setColor(new Color(255, 100, 100));
        // Access is now correct due to constants being public in BaseAssaultGame
        g.drawString("ENEMY BASE: " + enemyBase.hp, BaseAssaultGame.GAME_WIDTH - 200, baseHealthY);

        // Tower Status
        if (!playerBase.tower1Enabled) {
            g.setColor(Color.RED.brighter());
            g.drawString("TOWER DISABLED", baseHealthX + 200, baseHealthY);
        }
    }
}
