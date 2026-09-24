package com.oxygenraj.transactionui;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class ComponentBackend {
    private final BackendProperties properties;
    private final JsonGateway gateway;
    public ComponentBackend(BackendProperties properties,JsonGateway gateway) { this.properties=properties; this.gateway=gateway; }
    public record User(long id,String username) { }
    public record Page<T>(List<T> items,int page,int size,long totalElements,long totalPages) { }
    public record Transaction(long id,long userId,String monthName,int monthCount,BigDecimal amount,String createdAt,String modifiedAt,long version) { }
    public User signIn(String username,String password) {
        var result=gateway.request(properties.users(),"/authuserdetails",Map.of("username",username,"password",password));
        if (!result.path("authenticated").isBoolean() || !result.path("authenticated").booleanValue()) throw ApiFailure.invalid();
        return activeUser(result.path("user"));
    }
    public User activeUser(long id) { return activeUser(gateway.request(properties.users(),"/userdetails/"+id,null)); }
    private User activeUser(JsonNode value) {
        if(!value.path("enabled").isBoolean()) throw ApiFailure.invalid();
        if(!value.path("enabled").booleanValue()) throw new ApiFailure(401,"ACCOUNT_DISABLED","This account is disabled.");
        return user(value);
    }
    private User user(JsonNode value) { return new User(integer(value,"id",1),text(value,"username",64)); }
    public Page<User> users(int page,int size) {
        var result=gateway.request(properties.users(),"/userdetails?page="+page+"&size="+size,null);
        var content=result.path("content");
        long total=integer(result,"totalElements",0);
        if (!content.isArray() || content.size()>size || integer(result,"page",0)!=page || integer(result,"size",1)!=size) throw ApiFailure.invalid();
        List<User> items=new ArrayList<>();
        for(var value:content) items.add(user(value));
        return new Page<>(List.copyOf(items),page,size,total,pages(total,size));
    }
    public Page<Transaction> transactions(long userId,int page,int size) {
        var result=gateway.request(properties.transactions(),"/transactions?userId="+userId+"&page="+page+"&size="+size,null);
        var content=result.path("items");
        long total=integer(result,"totalElements",0);
        if(!content.isArray() || content.size()>size || integer(result,"page",0)!=page || integer(result,"size",1)!=size) throw ApiFailure.invalid();
        List<Transaction> items=new ArrayList<>();
        for(var value:content) {
            long responseUser=integer(value,"userId",1);
            if(responseUser!=userId) throw ApiFailure.invalid();
            var amount=value.path("amount");
            if(!amount.isNumber() || amount.decimalValue().signum()<0) throw ApiFailure.invalid();
            BigDecimal exactAmount=amount.decimalValue();
            if(exactAmount.scale() < -17 || exactAmount.scale()>2 || exactAmount.precision()-exactAmount.scale()>17) throw ApiFailure.invalid();
            long count=integer(value,"monthCount",1);
            if(count>Integer.MAX_VALUE) throw ApiFailure.invalid();
            items.add(new Transaction(integer(value,"id",1),responseUser,text(value,"monthName",20),(int)count,
                exactAmount,timestamp(value,"createdAt"),timestamp(value,"modifiedAt"),integer(value,"version",0)));
        }
        return new Page<>(List.copyOf(items),page,size,total,pages(total,size));
    }
    private long pages(long total,int size) { return total/size+(total%size==0?0:1); }
    private long integer(JsonNode object,String name,long min) {
        var value=object.path(name);
        if(!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<min) throw ApiFailure.invalid();
        return value.longValue();
    }
    private String text(JsonNode object,String name,int max) {
        var value=object.path(name);
        if(!value.isString() || value.stringValue().isBlank() || value.stringValue().length()>max) throw ApiFailure.invalid();
        return value.stringValue();
    }
    private String timestamp(JsonNode object,String name) {
        String value=text(object,name,64);
        try { OffsetDateTime.parse(value); return value; } catch(RuntimeException e) { throw ApiFailure.invalid(); }
    }
}
