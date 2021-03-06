package xyz.gghost.jskype.internal.threading;

import org.apache.commons.lang3.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import xyz.gghost.jskype.api.Skype;
import xyz.gghost.jskype.api.SkypeAPI;
import xyz.gghost.jskype.api.events.*;
import xyz.gghost.jskype.chat.Chat;
import xyz.gghost.jskype.internal.impl.Group;
import xyz.gghost.jskype.internal.packet.PacketBuilder;
import xyz.gghost.jskype.internal.packet.RequestType;
import xyz.gghost.jskype.internal.packet.packets.GetConvos;
import xyz.gghost.jskype.internal.packet.packets.GetProfilePacket;
import xyz.gghost.jskype.internal.packet.packets.GroupInfoPacket;
import xyz.gghost.jskype.var.*;

import java.util.ArrayList;

public class Poller extends Thread {

    private SkypeAPI api;
    private Skype usr;
    private String url;
    private String endpoint;
    private PacketBuilder packet;


    public Poller(SkypeAPI api, Skype usr) {
        this.api = api;
        this.usr = usr;
    }

    @Override
    public void run() {
        prepare();
        while (this.isAlive()) {
            poll();
        }
    }

    public void poll() {
        PacketBuilder poll = new PacketBuilder(api);
        poll.setType(RequestType.POST);
        poll.setUrl("https://" + url + "/v1/users/ME/endpoints/SELF/subscriptions/0/poll");
        poll.setData(" ");
        String data = poll.makeRequest(usr);
        if (data == null || data.equals("") || data.equals("{}"))
            return;

        JSONObject messagesAsJson = new JSONObject(data);
        JSONArray json = messagesAsJson.getJSONArray("eventMessages");
        int counta = 0;
        for (int i = 0; i < json.length(); i++) {
            JSONObject object = json.getJSONObject(i);
            try {
                if (!(object.isNull("type") && object.isNull("resourceType"))) {
                    Conversation chat = new Conversation(api, "", false);

                    if (object.getString("resourceLink").contains("conversations/19:") || object.getString("resourceLink").contains("8:")) {
                        if (!object.getString("resourceLink").contains("8:") ) {
                            String idShort = object.getString("resourceLink").split("conversations/19:")[1].split("@thread")[0];
                            addGroupToRecent(object);
                            Group group = usr.getGroupById(idShort);
                            chat.setId(group.getChatId());
                            chat.setForcedGroup(true);
                            chat.setForcedGroupGroup(group);
                            chat.setUserChat(false);
                            //Old skype for web bug that affects some users
                        } else if (!object.getString("resourceLink").contains("@")){
                            chat.setId(object.getString("resourceLink").split("/8:")[1].split("/")[0]);
                            chat.setUserChat(true);
                        }
                    } else {
                        if(api.isDebugMode())
                            System.out.println("Non-group data received from skype. This is ignorable.");
                    }

                    //thread update
                    if (object.getString("resourceType").equals("ThreadUpdate")) {
                        Conversation oldGroup = null;

                        ArrayList<String> oldUsers2 = new ArrayList<String>();
                        ArrayList<String> oldUsers = new ArrayList<String>();
                        ArrayList<GroupUser> users = new ArrayList<GroupUser>();
                        ArrayList<String> newUsers = new ArrayList<String>();

                        String shortId = object.getString("resourceLink").split("19:")[1].split("@")[0];
                        for (Conversation groups : usr.getConversations()) {
                            if (groups.getId().equals(shortId)) {
                                System.out.println(groups);
                                System.out.println(groups.getGroup().getConnectedClients());
                                for (GroupUser usr : groups.getGroup().getConnectedClients()) { //NULL POINTER HERE
                                    oldUsers.add(usr.getAccount().getUsername().toLowerCase());
                                    oldUsers2.add(usr.getAccount().getUsername().toLowerCase());
                                }
                                oldGroup = groups;
                            }
                        }

                        if (oldGroup != null) {

                            JSONObject resource = object.getJSONObject("resource");

                            String topic = oldGroup.getTopic();
                            String picture = resource.getJSONObject("properties").isNull("picture") ? "" : resource.getJSONObject("properties").getString("picture");

                            Group group = new Group(shortId, topic, null);
                            group.setPictureUrl(picture);
                            //user join/leave events
                            for (int ii = 0; ii < object.getJSONObject("resource").getJSONArray("members").length(); ii++) {
                                JSONObject user = object.getJSONObject("resource").getJSONArray("members").getJSONObject(ii);
                                newUsers.add(user.getString("id").replace("8:", ""));
                                try {
                                    Role role = Role.USER;
                                    User ussr = usr.getSimpleUser(user.getString("id").replace("8:", ""));
                                    if (!user.getString("role").equals("User"))
                                        role = Role.ADMIN;
                                    GroupUser gu = new GroupUser(ussr);
                                    gu.setRole(role);
                                    users.add(gu);
                                } catch (Exception e) {
                                }
                            }

                            group.setConnectedClients(users);
                            oldUsers.removeAll(newUsers);
                            newUsers.removeAll(oldUsers2);

                            usr.getConversations().remove(oldGroup);

                            Conversation newConvo = new Conversation(api, group.getChatId(), true);
                            newConvo.setForcedGroupGroup(group);
                            newConvo.setForcedGroup(true);

                            usr.getConversations().add(newConvo);

                            for (String old : oldUsers) {
                                if (!old.equals("live"))
                                    api.getEventManager().executeEvent(new UserLeaveEvent(chat, new GetProfilePacket(api, usr).getUser(old)));
                                if (!api.isDebugMode())
                                    return;
                            }
                            for (String news : newUsers) {
                                if (!news.equals("live"))
                                 api.getEventManager().executeEvent(new UserJoinEvent(chat, new GetProfilePacket(api, usr).getUser(news)));
                                if (!api.isDebugMode())
                                    return;
                            }
                        } else {
                            //added to the group
                        }

                    }
                    //Add to recent cache
                    if (!chat.isUserChat())
                        addGroupToRecent(object);

                    //resource json
                    JSONObject resource = object.getJSONObject("resource");

                    //Get topic update
                    if (!resource.isNull("messagetype") && resource.getString("messagetype").equals("ThreadActivity/TopicUpdate")) {
                        String topic = resource.getString("content").split("<value>")[1].split("<\\/value>")[0];
                        topic = Chat.decodeText(topic);

                        String username = resource.getString("content").split("<initiator>8:")[1].split("<\\/initiator>")[0];
                        String oldTopic = usr.getGroupById(chat.getId()).getTopic();

                        User user = getUser(username, chat);
                        api.getEventManager().executeEvent(new TopicChangedEvent(chat, user, topic, oldTopic));

                        usr.getGroupById(chat.getId()).setTopic(topic);
                    }

                    //Get Typing
                    if (!resource.isNull("messagetype") && resource.getString("messagetype").equals("Control/Typing")) {
                        User from = getUser(resource.getString("from").split("8:")[1], chat);
                        api.getEventManager().executeEvent(new UserTypingEvent(chat, from));
                    }


                    //Get message
                    if (!resource.isNull("messagetype") && (resource.getString("messagetype").equals("RichText") || resource.getString("messagetype").equals("Text"))) {
                        counta ++;

                        Message message = new Message();
                        User user = getUser(resource.getString("from").split("8:")[1], chat);

                        String content = "";
                        if(!resource.isNull("content"))
                            content = Chat.decodeText(resource.getString("content"));

                        if (!resource.isNull("clientmessageid"))
                            message.setId(resource.getString("clientmessageid"));

                        if (!resource.isNull("skypeeditedid")) {
                            content = content.replaceFirst("Edited previous message: ", "").split("<e_m")[0];
                            message.setId(resource.getString("skypeeditedid"));
                            message.setEdited(true);
                        }

                        message.setSender(user);
                        message.setTime(resource.getString("originalarrivaltime"));
                        message.setUpdateUrl(object.getString("resourceLink").split("/messages/")[0] + "/messages");
                        message.setMessage(content);

                        api.getEventManager().executeEvent(new UserChatEvent(chat, user, message));

                    }

                    //pings
                    if (!resource.isNull("messagetype") && resource.getString("messagetype").startsWith("RichText/")) {
                        User user = getUser(resource.getString("from").split("8:")[1], chat);
                        String content = resource.getString("content");
                        content = StringEscapeUtils.unescapeHtml4(content);
                        content = StringEscapeUtils.unescapeHtml3(content);
                        if (content.contains("To view this shared photo, go to: <a href=\"https://api.asm.skype.com/s/i?")) {

                            String id = content.split("To view this shared photo, go to: <a href=\"https://api.asm.skype.com/s/i?")[1].split("\">")[0];
                            String url = ("https://api.asm.skype.com/v1/objects/" + id + "/views/imgo").replace("objects/?", "objects/");
                            api.getEventManager().executeEvent(new UserImagePingEvent(chat, user, url));
                            return;
                        }
                        if (content.contains("<files alt=\"") && content.contains("<file size=")) {
                            api.getEventManager().executeEvent(new UserOtherFilesPingEvent(chat, user));
                            return;
                        }
                    }
                }

            }catch (Exception e) {
                System.out.println("Failed to process data from skype.\nMessage: "  + object + "Data: " + data + "\nError: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    //OLD METHODS
    private User getUser(String username, Conversation chat) {
        User user = null;
        //get user from contacts
        user = usr.getContact(username);
        //get user from connected clients
        if (user == null) {
            try {
                for (GroupUser users : chat.getConnectedClients()) {
                    if (users.getAccount().getUsername().equals(username))
                        user = users.getAccount();
                }
            } catch (NullPointerException e) {}
        }
        //If failed to get user - get the users info by calling skypes api
        if (user == null)
            user = new GetProfilePacket(api, usr).getUser(username);
        return user;
    }

    private void addGroupToRecent(JSONObject object) {
        String idLong = object.getString("resourceLink").split("conversations/")[1].split("/")[0];
        String idShort = object.getString("resourceLink").split("conversations/19:")[1].split("@thread")[0];
        //get if already exists
        for (Conversation group : usr.getConversations()) {
            if (group.getId().equals(idShort)) {
                return;
            }
        }
        usr.getConversations().add(new GroupInfoPacket(api, usr).getConvo(idLong));
    }

    //Old auth code
    public void prepare() {
        login();
        if (!reg()) {
            System.out.println("Failed to get update data from skype due to a login error... Attempting to relogin, however this wont work until the auto pinger kicks in.");
            login();
            try {
                Thread.sleep(1750);
                prepare();
            } catch (InterruptedException e) {}
        }
        save();
    }


    public void login() {
        url = location().split("://")[1].split("/")[0];
        usr.setRegToken(packet.getCon().getHeaderField("Set-RegistrationToken").split(";")[0]);
        endpoint = packet.getCon().getHeaderField("Set-RegistrationToken").split(";")[2].split("=")[1];
    }

    public boolean save() {
        String id = "{\"id\":\"messagingService\",\"type\":\"EndpointPresenceDoc\",\"selfLink\":\"uri\",\"publicInfo\":{\"capabilities\":\"video|audio\",\"type\":\"1\",\"skypeNameVersion\":\"skype.com\",\"nodeInfo\":\"2\",\"version\":\"2\"},\"privateInfo\":{\"epname\":\"Skype\"}}";
        PacketBuilder packet = new PacketBuilder(api);
        packet.setType(RequestType.PUT);
        packet.setData(id);
        packet.setUrl("https://" + url + "/v1/users/ME/endpoints/" + endpoint + "/presenceDocs/messagingService");
        return packet.makeRequest(usr) != null;
    }

    public boolean reg() {
        PacketBuilder packet = new PacketBuilder(api);
        String id = "{\"channelType\":\"httpLongPoll\",\"template\":\"raw\",\"interestedResources\":[\"/v1/users/ME/conversations/ALL/properties\",\"/v1/users/ME/conversations/ALL/messages\",\"/v1/users/ME/contacts/ALL\",\"/v1/threads/ALL\"]}";
        packet.setData(id);
        packet.setType(RequestType.POST);
        packet.setUrl("https://" + url + "/v1/users/ME/endpoints/SELF/subscriptions");
        return packet.makeRequest(usr) != null;
    }

    public String location() {
        packet = new PacketBuilder(api);
        packet.setUrl("https://client-s.gateway.messenger.live.com/v1/users/ME/endpoints");
        packet.setType(RequestType.POST);
        packet.setData("{}");
        String data = packet.makeRequest(usr);
        if (data == null) {
            //I have no fucking clue how to handle this. Crash application may be feasible but then after two days it might crash for no reason :/
            System.out.println("Null on location getter");
            System.exit(-1);

        }
        return packet.getCon().getHeaderField("Location");
    }

}
