package info.smartkit.godpaper.go.service;

import com.spotify.docker.client.exceptions.DockerException;
import com.toomasr.sgf4j.Sgf;
import info.smartkit.godpaper.go.dto.SgfDto;
import info.smartkit.godpaper.go.pojo.Gamer;
import info.smartkit.godpaper.go.pojo.User;
import info.smartkit.godpaper.go.repository.GamerRepository;
import info.smartkit.godpaper.go.repository.UserRepository;
import info.smartkit.godpaper.go.settings.*;
import info.smartkit.godpaper.go.utils.SgfUtil;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Autowired;
import info.smartkit.godpaper.go.settings.ApiProperties;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by smartkit on 22/06/2017.
 */
@Service
public class GamerServiceImpl implements GamerService {

        private static Logger LOG = LogManager.getLogger(GamerServiceImpl.class);;

        @Autowired GamerRepository gamerRepository;
        @Autowired MqttService mqttService;

        @Autowired
        private ChainCodeService chainCodeService;

        @Autowired ChainCodeProperties chainCodeProperties;
        @Autowired ApiProperties apiProperties;

        @Autowired UserRepository userRepository;
        @Autowired DockerService dockerService;


        private static final String ENCODING = "UTF-8";
        private static final String VERSION = "0.0.1";
        private static final int m_size = 19;
        private static final String RESULT_FIXTURE = "B?R";
        private static final String SGF_TAIL_FIXTURE = ")";

        @Override public List<Gamer> pairAll(List<User> tenantedUsers) throws MqttException {
                int arraySize = tenantedUsers.size();
                LOG.info("tenantedUsers("+tenantedUsers.size()+"):"+tenantedUsers.toString());
                //TODO:more game machine mechanism here.
                //ELO,Glicko,https://help.elo.com/ELOdoc/frame/9/javaclient/en/0100000001
                List<User> secondPart = tenantedUsers.subList(0,arraySize/2);
                List<User>  firstPart= tenantedUsers.subList(arraySize/2,arraySize);
                List<Gamer> gamers = new ArrayList<>(arraySize/2);
                for (int i=0;i<arraySize/2;i++) {
                        //produce message topic by uuid,for public message.
                        User player1 = firstPart.get(i);
                        User player2 = secondPart.get(i);
                        String vsTitle =  player1.getTopicName()+ MqttVariables.tag_vs+player2.getTopicName();
                        List<Gamer> existedGamers = gamerRepository.findByName(vsTitle);
                        LOG.info("existedGamers("+existedGamers.size()+"):"+existedGamers.toString());
                        if(existedGamers.size()==0) {//Do not existed.
                                Gamer gamer = new Gamer("",firstPart.get(i), secondPart.get(i), "");
                                //db save first.
                                gamer.setStatus(GameStatus.PAIRED.getIndex());
                                Gamer saved = gamerRepository.save(gamer);

                                //Send message
                                //                        ActivemqSender sender2Player1 = new ActivemqSender(player1.getTopicName());
                                //                        sender2Player1.sendMessage("echo");//For CREATE.CREATE
                                mqttService.subscribe(player1.getTopicName());
                                mqttService.publish(player1.getTopicName(), vsTitle, MqttQoS.EXCATLY_ONCE.getIndex());

                                //                        ActivemqSender sender2Player2 = new ActivemqSender(secondPart.get(i).getTopicName());
                                //                        sender2Player2.sendMessage("echo");//For CREATE.
                                mqttService.subscribe(player2.getTopicName());
                                mqttService.publish(player2.getTopicName(), vsTitle, MqttQoS.EXCATLY_ONCE.getIndex());
                                //
                                gamers.add(saved);
                        }else {
                                Gamer updateGamer =  existedGamers.get(0);
                                updateGamer.setStatus(GameStatus.PAIRED.getIndex());
                                Gamer updated = gamerRepository.save(updateGamer);
                                gamers.add(updated);
                        }
                }
                LOG.info("pairingGamers("+gamers.size()+"):"+gamers.toString());
                return gamers;
        }

        @Override public List<Gamer> playAll() throws MqttException, DockerException, InterruptedException, IOException {
                List<Gamer> pairedGames = gamerRepository.findByStatus(GameStatus.PAIRED.getIndex());
//                List<Gamer> playingGames = gamerRepository.findByStatus(GameStatus.PLAYING.getIndex());//TODO:resume-able game.
//                List<Gamer> playableGames = (pairedGames.size()>0)?pairedGames:playingGames;
                List<Gamer> playableGames = pairedGames;
                LOG.info("playableGames("+playableGames.size()+"):"+playableGames.toString());
                //find each player, send play notification
                for(int i=0;i<playableGames.size();i++) {
                        Gamer curGamer =  playableGames.get(i);
                        this.play(curGamer,i);
                }
                List<Gamer> updatedPairedGames = gamerRepository.findByStatus(GameStatus.PLAYING.getIndex());
                return updatedPairedGames;
        }

        @Override public Gamer playOne(String gamerId) throws MqttException, DockerException, InterruptedException, IOException {
                Gamer curGamer = gamerRepository.findOne(gamerId);
                //update users status
                User player1 = curGamer.getPlayer1();
                player1.setStatus(UserStatus.PLAYING.getIndex());
                userRepository.save(player1);
                User player2 = curGamer.getPlayer2();
                player2.setStatus(UserStatus.PLAYING.getIndex());
                userRepository.save(player2);
                return this.play(curGamer,1);
        }

        private Gamer play(Gamer gamer,int index) throws MqttException, DockerException, InterruptedException, IOException {
                LOG.info("this.play:#"+index+",is:"+gamer.toString());
                User player1 = gamer.getPlayer1();
                User player2 = gamer.getPlayer2();
                String playMessage = player1.getId()+MqttVariables.tag_play;
                //Game turn now
                //FIRST HAND
                mqttService.subscribe(player1.getTopicName());
                mqttService.publish(player1.getTopicName(),playMessage, MqttQoS.EXCATLY_ONCE.getIndex());
                //Second Hand
//                mqttService.subscribe(player2.getTopicName());
//                mqttService.publish(player2.getTopicName(),playMessage, MqttQoS.EXCATLY_ONCE.getIndex());
                //Update game status
                player1.setStatus(UserStatus.PLAYING.getIndex());
                player2.setStatus(UserStatus.STANDBY.getIndex());
                //Save game status
                gamer.setTopic(gamer.getTopic());
                //
//                gamer.setSgf(this.saveSgf(gamer,false,false).getCmd());
                gamer.setSgf(this.getSgfHeader(chainCodeProperties.getChainName(),VERSION,gamer,RESULT_FIXTURE));
                gamer.setStatus(GameStatus.PLAYING.getIndex());
                //
                Gamer savedGamer = gamerRepository.save(gamer);
                LOG.info("savedGamer#"+index+":"+savedGamer.toString());
                //subscribe game topic
                mqttService.subscribe(gamer.getTopic());
                if(chainCodeProperties.getEnabled()) {
                        //Register to ChainCode after deploy
                        String[] putArgs = { gamer.getId(), gamer.getSgf() };
                        chainCodeService.invoke(chainCodeProperties.getChainName(), chainCodeProperties.getEnrollId(), putArgs);
                }
                return gamer;
        }
//SGF block

        //remove last move of o.IllegalMove
        private void removeSgfLastMove(Gamer gamer) throws InterruptedException, DockerException, IOException {
                int lenOfSgf = gamer.getSgf().length();
                String validSgf = gamer.getSgf().substring(0, lenOfSgf - 6);//;B[sd]
                gamer.setSgf(validSgf+SGF_TAIL_FIXTURE);
                //
                LOG.info("removeSgfLastMove:"+gamer.getSgf());
        }

        private String saveSgfFileLocal(Gamer gamer) throws IOException, DockerException, InterruptedException {
                //
                String sgfFileName = gamer.getId()+"/"+this.writeSgfFile(gamer).getName();
                String destFileStr = SgfUtil.getSgfLocal(sgfFileName);
                //copy sgf file to sgf/gamerId folder.
                File destFile = new File(destFileStr);
                FileUtils.copyFile(sgfFile,destFile);//for url.
                LOG.info("copy sgf file to sgf/{gamerId} folder success:"+sgfFileName);
                return destFileStr;
        }

        private File sgfFile;
        private File writeSgfFile(Gamer gamer){
                //
                sgfFile =  Sgf.writeToFile(gamer.getSgf());
                LOG.info("sgfFile:"+sgfFile.toString());
                return sgfFile;
        }

        public String getSgfResult(Gamer gamer) throws IOException, DockerException, InterruptedException {
                String resultStr =  dockerService.runScorer(gamer.getId());
                //remove temp sgf files.
//                FileUtils.cleanDirectory(new File(SgfUtil.getSgfLocal(gamer.getId())));
               return resultStr;
        }

        //@see: https://github.com/jromang/gogui/blob/master/src/net/sf/gogui/sgf/SgfWriter.java
        @Override public SgfDto saveSgf(Gamer gamer) throws IOException, DockerException, InterruptedException {
                //
                SgfDto sgfDto = new SgfDto();
                //
                this.removeSgfLastMove(gamer);
                //for agent training.
                sgfDto.setName(gamer.getId());
                sgfDto.setLocal(this.saveSgfFileLocal(gamer));
                //url
                String sgfFileName = gamer.getId()+"/"+sgfFile.getName();
                String sgfUrl = apiProperties.getUrl()+"sgf/"+sgfFileName;
                sgfDto.setUrl(sgfUrl);
                //result
                sgfDto.setResult(this.getSgfResult(gamer));
                //update game status
                gamer.setStatus(GameStatus.SAVED.getIndex());
                Gamer updated = gamerRepository.save(gamer);
                LOG.info("gamer status to SAVED:"+updated.toString());
                //
                return sgfDto;
        }

        @Override public void createFolder(String name) throws IOException {
                //mkdir
                File sgfFolder = new File(SgfUtil.getSgfLocal(name));
                FileUtils.forceMkdir(sgfFolder);
        }

        @Override public void deleteFolder(String name) throws IOException {
                FileUtils.deleteDirectory(new File(SgfUtil.getSgfLocal(name)));
        }

        private String getSgfHeader(String application, String version,Gamer gamer,String resultStr)
        {
                StringBuilder header = new StringBuilder(128);
                header.append("(;FF[4]CA[");
                header.append(getEscaped(ENCODING));
                header.append(']');
                if (application != null && ! application.equals(""))
                {
                        String appName = application;
                        if (version != null && ! version.equals(""))
                                appName = appName + ":" + version;
                        header.append("AP[");
                        header.append(getEscaped(appName));
                        header.append(']');
                }
                        header.append("SZ[");
                        header.append(m_size);
                        header.append(']');
                        header.append("SO[");
                        header.append(gamer.getId());
                        header.append("]");
                        //come from
                        header.append("BC[cn]");
                        header.append("WC[cn]");
                        //player1
                        //name
                        User player1 = gamer.getPlayer1();
                        header.append("PB[").append(player1.getName()).append(']');
                        //rank
                        header.append("BR[").append(player1.getRank()).append("p]");
                        //player2
                        User player2 = gamer.getPlayer2();
                        header.append("PW[").append(player2.getName()).append(']');
                        //rank
                        header.append("WR[").append(player2.getRank()).append("p]");
                        //TODO:komi
                        header.append("KM[").append("0").append("]");
                        //DateTime
                        header.append("DT[").append(gamer.getCreated()).append("]");
                        //gnugo/ogs
                        header.append("RE[").append(resultStr).append("]");//default.
                        //
                return header.toString();
        }


        private String getEscaped(String text)
        {
                return getEscaped(text, false);
        }

        private String getEscaped(String text, boolean escapeColon)
        {
                StringBuilder result = new StringBuilder(2 * text.length());
                for (int i = 0; i < text.length(); ++i)
                {
                        char c = text.charAt(i);
                        String specialCharacters;
                        if (escapeColon)
                                specialCharacters = "]:\\";
                        else
                                specialCharacters = "]\\";
                        if (specialCharacters.indexOf(c) >= 0)
                        {
                                result.append('\\');
                                result.append(c);
                        }
                        else if (c != '\n' && Character.isWhitespace(c))
                                result.append(' ');
                        else
                                result.append(c);
                }
                return result.toString();
        }
}
