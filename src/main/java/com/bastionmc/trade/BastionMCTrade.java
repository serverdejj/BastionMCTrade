package com.bastionmc.trade;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.file.FileConfiguration;
import java.io.File;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

public final class BastionMCTrade extends JavaPlugin implements Listener {
    private final Map<UUID, Request> requests = new HashMap<>();
    private final Map<UUID, Trade> active = new HashMap<>();
    private final Map<Inventory, View> views = new IdentityHashMap<>();
    private Economy economy;
    private FileConfiguration messages;
    private static final int[] OFFER = {0,1,2,3,9,10,11,12,18,19,20,21,27,28,29,30,36,37,38,39};
    private static final int[] OTHER = {5,6,7,8,14,15,16,17,23,24,25,26,32,33,34,35,41,42,43,44};

    private record Request(UUID from, UUID to, long expires) {}
    private record View(Trade trade, UUID owner, boolean moneyMenu) {}
    private static final class Trade {
        final UUID a,b;
        final Map<UUID, ItemStack[]> offers = new HashMap<>();
        final Map<UUID, Double> money = new HashMap<>();
        final Map<UUID, Boolean> accepted = new HashMap<>();
        final Map<UUID, Inventory> inventories = new HashMap<>();
        BukkitTask timer;
        int remaining = -1;
        boolean ending;
        Trade(UUID a, UUID b) {
            this.a=a; this.b=b;
            for (UUID id : new UUID[]{a,b}) { offers.put(id,new ItemStack[20]); money.put(id,0d); accepted.put(id,false); }
        }
        UUID other(UUID id) { return id.equals(a) ? b : a; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        loadMessages();
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (provider != null) economy = provider.getProvider();
        }
        if (economy == null) getLogger().warning("No se detectó proveedor de economía Vault. Solo funcionarán objetos.");
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("trade")).setExecutor((sender,cmd,label,args) -> {
            if (args.length>0 && (args[0].equalsIgnoreCase("reload"))) {
                if (!sender.hasPermission("bastiontrade.admin")) { sender.sendMessage(color(message("no-permission-admin"))); return true; }
                reloadConfig(); loadMessages();
                sender.sendMessage(color(message("reload-success")));
                return true;
            }
            if (!(sender instanceof Player p)) { sender.sendMessage(color(message("only-players"))); return true; }
            if (args.length < 1) { msg(p,"usage-interact"); return true; }
            if (args[0].equalsIgnoreCase("accept")) accept(p,args.length>1?args[1]:null);
            else if (args[0].equalsIgnoreCase("deny")) deny(p,args.length>1?args[1]:null);
            else msg(p,"usage-command");
            return true;
        });
        Objects.requireNonNull(getCommand("bastiontrade")).setExecutor(Objects.requireNonNull(getCommand("trade")).getExecutor());
    }
    @Override public void onDisable() {
        for (Trade t : new HashSet<>(active.values())) cancel(t,"cancel-server-stop");
        views.clear(); requests.clear();
    }
    private void loadMessages() {
        File file = new File(getDataFolder(), "messages.yml");
        if (!file.exists()) saveResource("messages.yml", false);
        messages = YamlConfiguration.loadConfiguration(file);
    }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private String message(String key, Object... replacements) {
        String value = messages.getString(key);
        if (value == null) value = "&c[Missing: " + key + "]";
        for (int i=0; i+1<replacements.length; i+=2)
            value = value.replace("%"+replacements[i]+"%", String.valueOf(replacements[i+1]));
        return value;
    }
    private void msg(Player p,String key,Object... replacements) {
        if(p!=null && p.isOnline()) p.sendMessage(color(message("prefix")+message(key,replacements)));
    }
    private String menu(String key,Object... replacements) { return color(message("menu."+key,replacements)); }
    private ItemStack ui(Material mat,String key,Object... replacements) {
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(menu(key+".name",replacements));
        List<String> lore = messages.getStringList("menu."+key+".lore");
        if (!lore.isEmpty()) {
            List<String> output = new ArrayList<>();
            for (String line : lore) {
                for (int i=0; i+1<replacements.length; i+=2)
                    line = line.replace("%"+replacements[i]+"%", String.valueOf(replacements[i+1]));
                output.add(color(line));
            }
            meta.setLore(output);
        }
        stack.setItemMeta(meta);
        return stack;
    }
    private boolean nearby(Player a,Player b) {
        return a!=null && b!=null && a.isOnline() && b.isOnline() && a.getWorld().equals(b.getWorld())
            && a.getLocation().distanceSquared(b.getLocation()) <= Math.pow(getConfig().getDouble("max-distance",5),2);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void interact(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Player target) || !e.getPlayer().isSneaking()) return;
        e.setCancelled(true);
        Player from=e.getPlayer();
        if (!from.hasPermission("bastiontrade.use") || !target.hasPermission("bastiontrade.use")) { msg(from,"no-permission"); return; }
        if (active.containsKey(from.getUniqueId()) || active.containsKey(target.getUniqueId())) { msg(from,"already-trading"); return; }
        if (!nearby(from,target)) {msg(from,"too-far");return;}
        long now=System.currentTimeMillis();
        if (requests.values().stream().anyMatch(r->r.expires>now && (r.from.equals(from.getUniqueId()) || r.to.equals(target.getUniqueId())))) {
            msg(from,"request-pending"); return;
        }
        Request request=new Request(from.getUniqueId(),target.getUniqueId(),now+Math.max(1,getConfig().getInt("request-seconds",30))*1000L);
        requests.put(target.getUniqueId(),request);
        msg(from,"request-sent","player",target.getName());
        msg(target,"request-received","player",from.getName(),"seconds",getConfig().getInt("request-seconds",30));
        target.sendMessage(net.kyori.adventure.text.Component.text(color(message("request-accept-button")+" "))
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/trade accept "+from.getName()))
            .append(net.kyori.adventure.text.Component.text(color(message("request-deny-button")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/trade deny "+from.getName()))));
        Bukkit.getScheduler().runTaskLater(this,()->{
            if (requests.remove(target.getUniqueId(),request)) {
                msg(from,"request-expired-sender","player",target.getName());
                msg(target,"request-expired-target","player",from.getName());
            }
        },Math.max(1,getConfig().getInt("request-seconds",30))*20L);
    }
    private void accept(Player target,String fromName) {
        Request r=requests.get(target.getUniqueId()); Player from=r==null?null:Bukkit.getPlayer(r.from());
        if (r==null || System.currentTimeMillis()>r.expires() || from==null || (fromName!=null&&!from.getName().equalsIgnoreCase(fromName))) {
            msg(target,"no-request"); return;
        }
        requests.remove(target.getUniqueId());
        if (active.containsKey(target.getUniqueId()) || active.containsKey(from.getUniqueId()) || !nearby(from,target)) {
            msg(target,"unable-to-start"); return;
        }
        Trade t=new Trade(from.getUniqueId(),target.getUniqueId());
        active.put(t.a,t);active.put(t.b,t);
        for(Player p : new Player[]{from,target}) {
            Inventory inv=Bukkit.createInventory(null,54,menu("trade-title"));
            t.inventories.put(p.getUniqueId(),inv); views.put(inv,new View(t,p.getUniqueId(),false));
        }
        redraw(t);
        for(Player p : new Player[]{from,target}) p.openInventory(t.inventories.get(p.getUniqueId()));
        msg(from,"trade-started","player",target.getName());msg(target,"trade-started","player",from.getName());
    }
    private void deny(Player target,String fromName) {
        Request r=requests.get(target.getUniqueId()); Player from=r==null?null:Bukkit.getPlayer(r.from());
        if (r==null || (fromName!=null && (from==null||!from.getName().equalsIgnoreCase(fromName)))) { msg(target,"no-request");return; }
        requests.remove(target.getUniqueId());msg(target,"request-denied");msg(from,"request-denied-sender","player",target.getName());
    }
    private void redraw(Trade t) {
        for(UUID owner : new UUID[]{t.a,t.b}) {
            Inventory inv=t.inventories.get(owner); if(inv==null) continue;
            inv.clear();UUID rival=t.other(owner);
            for(int i=0;i<20;i++) {inv.setItem(OFFER[i],t.offers.get(owner)[i]);inv.setItem(OTHER[i],t.offers.get(rival)[i]);}
            for(int j=0;j<5;j++)inv.setItem(4+j*9,ui(Material.GRAY_STAINED_GLASS_PANE,"separator"));
            for(int j=45;j<54;j++) inv.setItem(j,ui(Material.BLACK_STAINED_GLASS_PANE,"filler"));
            inv.setItem(45,ui(t.accepted.get(owner)?Material.LIME_CONCRETE:Material.LIME_DYE,t.accepted.get(owner)?"accepted":"accept"));
            inv.setItem(46,ui(Material.RED_DYE,"cancel"));
            inv.setItem(49,ui(Material.GOLD_INGOT,"money-offer","money",format(t.money.get(owner)),"other_money",format(t.money.get(rival))));
            String status=t.remaining>=0?"countdown":t.accepted.get(rival)?"rival-accepted":"waiting";
            inv.setItem(53,ui(t.remaining>=0?Material.CLOCK:Material.PAPER,status,"seconds",t.remaining));
            inv.setItem(47,ui(Material.LIGHT_GRAY_STAINED_GLASS_PANE,"filler"));
            inv.setItem(48,ui(Material.LIGHT_GRAY_STAINED_GLASS_PANE,"filler"));
            inv.setItem(50,ui(Material.LIGHT_GRAY_STAINED_GLASS_PANE,"filler"));
            inv.setItem(51,ui(Material.LIGHT_GRAY_STAINED_GLASS_PANE,"filler"));
            inv.setItem(52,ui(Material.LIGHT_GRAY_STAINED_GLASS_PANE,"filler"));
        }
    }
    private String format(double value){ return String.format(java.util.Locale.US,"%,.2f",value); }
    private void changed(Trade t) {t.accepted.put(t.a,false);t.accepted.put(t.b,false);redraw(t);}
    private int index(int[] slots,int slot) {for(int i=0;i<slots.length;i++)if(slots[i]==slot)return i;return -1;}
    @EventHandler public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p))return;
        View view=views.get(e.getView().getTopInventory());
        if(view==null)return;
        e.setCancelled(true);
        Trade t=view.trade();if(t.ending)return;
        if(!active.containsKey(p.getUniqueId()) || !view.owner().equals(p.getUniqueId()))return;
        if(t.remaining>=0)return;
        if(view.moneyMenu()) {
            if(e.getClickedInventory()!=e.getView().getTopInventory())return;
            double current=t.money.get(p.getUniqueId());
            int slot=e.getRawSlot(); int small=getConfig().getInt("money-step-small",10), medium=getConfig().getInt("money-step-medium",100), large=getConfig().getInt("money-step-large",1000);
            double change=switch(slot) {case 10 -> -large;case 11 -> -medium;case 12 -> -small;case 14 -> small;case 15 -> medium;case 16 -> large;default -> 0;};
            if(change!=0) {
                if(economy==null){msg(p,"no-economy");return;}
                double value=Math.max(0,Math.round((current+change)*100.0)/100.0);
                if(value>economy.getBalance(p)){msg(p,"not-enough-money");return;}
                t.money.put(p.getUniqueId(),value);changed(t);showMoney(p,t);return;
            }
            if(slot==22||slot==18) {views.remove(e.getView().getTopInventory());p.openInventory(t.inventories.get(p.getUniqueId()));}
            return;
        }
        if(e.getClickedInventory()!=e.getView().getTopInventory()) {
            if(e.isShiftClick() && e.getClickedInventory()==p.getInventory() && e.getCurrentItem()!=null && e.getCurrentItem().getType()!=Material.AIR) {
                int free=firstFree(t.offers.get(p.getUniqueId()));if(free<0) {msg(p,"offer-full");return;}
                t.offers.get(p.getUniqueId())[free]=e.getCurrentItem().clone();e.getClickedInventory().setItem(e.getSlot(),null);changed(t);
            }
            return;
        }
        int slot=e.getRawSlot(); int own=index(OFFER,slot);
        if(own>=0) {
            ItemStack[] offer=t.offers.get(p.getUniqueId());ItemStack held=e.getCursor();
            if(offer[own]!=null && offer[own].getType()!=Material.AIR) {
                if(held!=null&&held.getType()!=Material.AIR) {msg(p,"cursor-not-empty");return;}
                p.setItemOnCursor(offer[own].clone());offer[own]=null;changed(t);
            } else if(held!=null&&held.getType()!=Material.AIR) {
                offer[own]=held.clone();p.setItemOnCursor(null);changed(t);
            }
            return;
        }
        if(slot==46){cancel(t,"cancel-by-player");return;}
        if(slot==49){showMoney(p,t);return;}
        if(slot==45) {
            if(!nearby(Bukkit.getPlayer(t.a),Bukkit.getPlayer(t.b))) {cancel(t,"cancel-distance");return;}
            if(t.money.get(t.a)>0||t.money.get(t.b)>0) {
                if(economy==null){msg(p,"no-economy");return;}
                if(economy.getBalance(Bukkit.getOfflinePlayer(t.a))<t.money.get(t.a)||economy.getBalance(Bukkit.getOfflinePlayer(t.b))<t.money.get(t.b)) {msg(p,"offer-no-balance");return;}
            }
            t.accepted.put(p.getUniqueId(),true);redraw(t);
            if(t.accepted.get(t.a)&&t.accepted.get(t.b))startTimer(t);
        }
    }
    private int firstFree(ItemStack[] items){for(int i=0;i<items.length;i++)if(items[i]==null||items[i].getType()==Material.AIR)return i;return -1;}
    private void showMoney(Player p,Trade t) {
        Inventory inv=Bukkit.createInventory(null,27,menu("money-title"));views.put(inv,new View(t,p.getUniqueId(),true));
        for(int i=0;i<27;i++)inv.setItem(i,ui(Material.GRAY_STAINED_GLASS_PANE,"filler"));
        inv.setItem(10,ui(Material.RED_STAINED_GLASS_PANE,"subtract-money","amount",getConfig().getInt("money-step-large",1000)));
        inv.setItem(11,ui(Material.RED_STAINED_GLASS_PANE,"subtract-money","amount",getConfig().getInt("money-step-medium",100)));
        inv.setItem(12,ui(Material.RED_STAINED_GLASS_PANE,"subtract-money","amount",getConfig().getInt("money-step-small",10)));
        inv.setItem(13,ui(Material.GOLD_INGOT,"money-current","money",format(t.money.get(p.getUniqueId())),"balance",economy==null?message("balance-unavailable"):format(economy.getBalance(p))));
        inv.setItem(14,ui(Material.LIME_STAINED_GLASS_PANE,"add-money","amount",getConfig().getInt("money-step-small",10)));
        inv.setItem(15,ui(Material.LIME_STAINED_GLASS_PANE,"add-money","amount",getConfig().getInt("money-step-medium",100)));
        inv.setItem(16,ui(Material.LIME_STAINED_GLASS_PANE,"add-money","amount",getConfig().getInt("money-step-large",1000)));
        inv.setItem(18,ui(Material.ARROW,"money-back"));inv.setItem(22,ui(Material.LIME_DYE,"money-confirm"));
        p.openInventory(inv);
    }
    private void startTimer(Trade t) {
        t.remaining=Math.max(1,getConfig().getInt("countdown-seconds",5));redraw(t);
        t.timer=Bukkit.getScheduler().runTaskTimer(this,()->{
            if(t.ending)return;
            Player a=Bukkit.getPlayer(t.a),b=Bukkit.getPlayer(t.b);
            if(!nearby(a,b)) {cancel(t,"cancel-distance");return;}
            if(!t.accepted.get(t.a)||!t.accepted.get(t.b)){if(t.timer!=null)t.timer.cancel();t.remaining=-1;redraw(t);return;}
            if(--t.remaining<=0)complete(t);else redraw(t);
        },20L,20L);
    }
    private boolean withdraw(Player p,double amount) {
        return amount==0 || (economy!=null && economy.withdrawPlayer(p,amount).transactionSuccess());
    }
    private void complete(Trade t) {
        if(t.ending)return;
        Player a=Bukkit.getPlayer(t.a),b=Bukkit.getPlayer(t.b);
        if(!nearby(a,b)) {cancel(t,"cancel-distance");return;}
        double am=t.money.get(t.a),bm=t.money.get(t.b);
        if((am>0||bm>0) && (economy==null||economy.getBalance(a)<am||economy.getBalance(b)<bm)) {cancel(t,"cancel-no-balance");return;}
        if(!withdraw(a,am)) {cancel(t,"cancel-withdraw");return;}
        if(!withdraw(b,bm)) {if(am>0)economy.depositPlayer(a,am);cancel(t,"cancel-withdraw");return;}
        // El intercambio de dinero se procesa antes de mover objetos; los reembolsos se intentan si falla un ingreso.
        boolean paidA=bm==0 || economy.depositPlayer(a,bm).transactionSuccess();
        boolean paidB=am==0 || economy.depositPlayer(b,am).transactionSuccess();
        if(!paidA || !paidB) {
            getLogger().severe("Fallo de depósito Vault en trade "+t.a+" / "+t.b+". Revisar economía manualmente.");
            // No se mueven objetos cuando la economía no ha confirmado ambos depósitos.
            cancel(t,"cancel-economy-error");return;
        }
        t.ending=true;if(t.timer!=null)t.timer.cancel();
        for(ItemStack item:t.offers.get(t.a))give(b,item);
        for(ItemStack item:t.offers.get(t.b))give(a,item);
        msg(a,"trade-completed");msg(b,"trade-completed");
        finish(t);
    }
    private void give(Player p,ItemStack stack) {
        if(p==null||stack==null||stack.getType()==Material.AIR)return;
        Map<Integer,ItemStack> overflow=p.getInventory().addItem(stack.clone());
        for(ItemStack excess:overflow.values())p.getWorld().dropItemNaturally(p.getLocation(),excess);
    }
    private void cancel(Trade t,String reason) {
        if(t.ending)return;t.ending=true;if(t.timer!=null)t.timer.cancel();
        for(UUID id:new UUID[]{t.a,t.b}) {
            Player p=Bukkit.getPlayer(id);
            if(p!=null)for(ItemStack stack:t.offers.get(id))give(p,stack);
            msg(p,"trade-cancelled","reason",message(reason));
        }
        finish(t);
    }
    private void finish(Trade t) {
        active.remove(t.a,t);active.remove(t.b,t);
        for(UUID id:new UUID[]{t.a,t.b}) {
            Inventory inventory=t.inventories.get(id);
            if(inventory!=null)views.remove(inventory);
            Player p=Bukkit.getPlayer(id);
            if(p!=null&&p.isOnline()) {
                Inventory top=p.getOpenInventory().getTopInventory();
                View v=views.get(top);
                if(v!=null&&v.trade()==t)views.remove(top);
                p.closeInventory();
            }
        }
    }
    @EventHandler public void close(InventoryCloseEvent e) {
        View view=views.get(e.getInventory());if(view==null)return;
        if(view.moneyMenu()) views.remove(e.getInventory());
        if(view.trade().ending)return;
        // Al navegar al submenú de dinero, la vista anterior es un inventario de la sesión.
        Bukkit.getScheduler().runTask(this,()->{
            Trade t=view.trade();if(t.ending)return;
            Player p=Bukkit.getPlayer(view.owner());if(p==null)return;
            View next=views.get(p.getOpenInventory().getTopInventory());
            if(next==null||next.trade()!=t)cancel(t,"cancel-closed");
        });
    }
    @EventHandler public void drag(InventoryDragEvent e) {
        if(views.containsKey(e.getView().getTopInventory()))e.setCancelled(true);
    }
    @EventHandler public void quit(PlayerQuitEvent e) {
        Player p=e.getPlayer();requests.remove(p.getUniqueId());requests.entrySet().removeIf(entry->entry.getValue().from.equals(p.getUniqueId()));
        Trade t=active.get(p.getUniqueId());if(t!=null)cancel(t,"cancel-disconnect");
    }
}
